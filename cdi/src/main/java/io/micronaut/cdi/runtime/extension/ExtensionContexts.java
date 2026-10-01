/*
 * Copyright 2017-2026 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.cdi.runtime.extension;

import io.micronaut.context.BeanContext;
import io.micronaut.context.RuntimeBeanDefinition;
import io.micronaut.context.annotation.Context;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.annotation.Order;
import io.micronaut.core.order.Ordered;
import io.micronaut.core.type.Argument;
import io.micronaut.inject.BeanDefinition;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.spi.AlterableContext;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Stands up the contexts a build compatible extension registered with
 * {@code MetaAnnotations.addContext} (section 2.10.1), and remembers them for the container to answer
 * {@code getContext} and {@code getContexts} from.
 *
 * <p>The discovery phase runs while the application compiles, and a bean definition is generated there for
 * every context class an extension registered, recording the scope the context serves. Here those definitions
 * are read, one instance of every context class is obtained from its definition, and a Micronaut custom scope
 * is registered for each scope so that resolution of a bean in it goes through the context the extension
 * provided.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Context
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@Internal
public final class ExtensionContexts {

    private final BeanContext beanContext;
    private final Map<String, List<AlterableContext>> contextsByScope = new LinkedHashMap<>();
    private final Set<String> normalScopes = new LinkedHashSet<>();

    private final List<String> registeredQualifiers = new ArrayList<>();
    private final List<String[]> registeredNonbindingMembers = new ArrayList<>();

    public ExtensionContexts(BeanContext beanContext) {
        this.beanContext = beanContext;
    }

    @jakarta.annotation.PreDestroy
    void standDown() {
        // this container's say is withdrawn: another container that registered the same names keeps its own
        for (String name : registeredQualifiers) {
            io.micronaut.cdi.runtime.ExtensionQualifiers.deregister(name);
        }
        for (String[] member : registeredNonbindingMembers) {
            io.micronaut.cdi.runtime.ExtensionQualifiers.deregisterNonbindingMember(member[0], member[1]);
        }
    }

    @PostConstruct
    void standUp() {
        List<BeanDefinition<?>> contextDefinitions = new ArrayList<>();
        for (BeanDefinition<?> definition : beanContext.getAllBeanDefinitions()) {
            List<String> qualifierNames = new ArrayList<>(definition.getAnnotationMetadata()
                .getAnnotationNamesByStereotype(io.micronaut.core.annotation.AnnotationUtil.QUALIFIER));
            qualifierNames.addAll(definition.getAnnotationMetadata()
                .getAnnotationNamesByStereotype("jakarta.inject.Qualifier"));
            for (String qualifierName : qualifierNames) {
                // an annotation an extension made a qualifier of does not say so on its own class: what the
                // compiled metadata knows, the runtime checks are told
                io.micronaut.cdi.runtime.ExtensionQualifiers.register(qualifierName);
                registeredQualifiers.add(qualifierName);
            }
            AnnotationValue<io.micronaut.cdi.internal.metadata.CdiExtensionQualifiers> qualifiers =
                definition.getAnnotationMetadata()
                    .getAnnotation(io.micronaut.cdi.internal.metadata.CdiExtensionQualifiers.class);
            if (qualifiers != null) {
                for (String entry : qualifiers.stringValues()) {
                    String[] parts = entry.split("\\|", -1);
                    io.micronaut.cdi.runtime.ExtensionQualifiers.register(parts[0]);
                    registeredQualifiers.add(parts[0]);
                    if (parts.length > 1 && !parts[1].isEmpty()) {
                        for (String member : parts[1].split(";")) {
                            io.micronaut.cdi.runtime.ExtensionQualifiers
                                .registerNonbindingMember(parts[0], member);
                            registeredNonbindingMembers.add(new String[] {parts[0], member});
                        }
                    }
                }
            }
            if (definition.getAnnotationMetadata()
                .hasAnnotation(io.micronaut.cdi.internal.metadata.CdiRegisteredContext.class)) {
                contextDefinitions.add(definition);
            }
        }
        // one instance of every context class, whichever compilations of the application registered it
        Set<String> seenContexts = new LinkedHashSet<>();
        for (BeanDefinition<?> definition : contextDefinitions) {
            AnnotationValue<io.micronaut.cdi.internal.metadata.CdiRegisteredContext> registered =
                definition.getAnnotationMetadata()
                    .getAnnotation(io.micronaut.cdi.internal.metadata.CdiRegisteredContext.class);
            Class<? extends Annotation> scopeAnnotation = registered == null ? null : scopeOf(registered);
            if (scopeAnnotation == null) {
                throw new IllegalStateException("The scope annotation of the context "
                    + definition.getBeanType().getName() + " is not on the classpath");
            }
            String scopeName = scopeAnnotation.getName();
            if (!seenContexts.add(scopeName + "|" + definition.getBeanType().getName())) {
                continue;
            }
            // the context is the one the definition the compiler generated for its class creates
            AlterableContext context = (AlterableContext) beanContext.getBean(definition);
            List<AlterableContext> contexts = contextsByScope.get(scopeName);
            if (contexts != null) {
                contexts.add(context);
                continue;
            }
            List<AlterableContext> contextsOfScope = new ArrayList<>();
            contextsOfScope.add(context);
            contextsByScope.put(scopeName, contextsOfScope);
            if (registered != null && registered.booleanValue("normal").orElse(false)) {
                normalScopes.add(scopeName);
            }
            beanContext.registerBeanDefinition(RuntimeBeanDefinition
                .builder(io.micronaut.context.scope.CustomScope.class,
                    () -> new ExtensionCustomScope(scopeAnnotation, contextsOfScope, beanContext))
                .singleton(true)
                .typeArguments(Argument.of(scopeAnnotation))
                .build());
        }
    }

    /**
     * Registers a context a portable extension handed the container as it started, for the scope it serves. A
     * context for a built-in normal scope takes the place of the container's own: the scope is served by the
     * annotation the compiler read it into.
     *
     * @param context The context
     * @param normal  Whether the scope is a normal one
     * @throws IllegalArgumentException For a context of a pseudo-scope the container implements itself
     */
    public void register(AlterableContext context, boolean normal) {
        Class<? extends Annotation> scopeAnnotation = context.getScope();
        String scopeName = scopeAnnotation.getName();
        if (scopeName.equals("jakarta.enterprise.context.Dependent") || scopeName.equals("jakarta.inject.Singleton")) {
            throw new IllegalArgumentException("A context cannot be added for the pseudo-scope " + scopeName);
        }
        List<AlterableContext> contexts = contextsByScope.get(scopeName);
        if (contexts != null) {
            contexts.add(context);
            return;
        }
        List<AlterableContext> contextsOfScope = new ArrayList<>();
        contextsOfScope.add(context);
        contextsByScope.put(scopeName, contextsOfScope);
        if (normal) {
            normalScopes.add(scopeName);
        }
        Class<? extends Annotation> served = switch (scopeName) {
            case "jakarta.enterprise.context.ApplicationScoped" -> io.micronaut.cdi.internal.metadata.CdiApplicationScope.class;
            case "jakarta.enterprise.context.RequestScoped" -> io.micronaut.cdi.internal.metadata.CdiRequestScope.class;
            default -> scopeAnnotation;
        };
        RuntimeBeanDefinition.Builder<io.micronaut.context.scope.CustomScope> builder = RuntimeBeanDefinition
            .builder(io.micronaut.context.scope.CustomScope.class,
                () -> new ExtensionCustomScope(served, contextsOfScope, beanContext))
            .singleton(true)
            .typeArguments(Argument.of(served));
        if (served == io.micronaut.cdi.internal.metadata.CdiApplicationScope.class) {
            builder.replaces(io.micronaut.cdi.context.ApplicationScope.class);
        } else if (served == io.micronaut.cdi.internal.metadata.CdiRequestScope.class) {
            builder.replaces(io.micronaut.cdi.context.RequestScope.class);
        }
        beanContext.registerBeanDefinition(builder.build());
    }

    /**
     * Makes an annotation a qualifier for as long as this container runs, as a portable extension may ask.
     *
     * @param annotationName The name of the annotation
     */
    public void registerQualifier(String annotationName) {
        io.micronaut.cdi.runtime.ExtensionQualifiers.register(annotationName);
        registeredQualifiers.add(annotationName);
    }

    /**
     * Registers a portable extension as the bean the specification has it be: one instance, with the default
     * qualifier, which a program can look up and inject.
     *
     * @param extension The extension
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void registerExtension(Object extension) {
        io.micronaut.inject.annotation.MutableAnnotationMetadata metadata =
            new io.micronaut.inject.annotation.MutableAnnotationMetadata();
        metadata.addDeclaredAnnotation("jakarta.enterprise.inject.Default", Map.of());
        metadata.addDeclaredStereotype(List.of("jakarta.enterprise.inject.Default"),
            io.micronaut.core.annotation.AnnotationUtil.QUALIFIER, Map.of());
        // section 3.9 has the bean of an extension application scoped; there is one instance and no proxy
        metadata.addDeclaredAnnotation(io.micronaut.cdi.internal.metadata.CdiScope.class.getName(),
            Map.of("value", "jakarta.enterprise.context.ApplicationScoped", "normal", false));
        beanContext.registerBeanDefinition(RuntimeBeanDefinition
            .builder((Class) extension.getClass(), () -> extension)
            .singleton(true)
            .annotationMetadata(metadata)
            .build());
    }

    /**
     * Whether the extension registered the given scope as a normal one.
     *
     * @param scopeAnnotation The scope
     * @return Whether it is normal
     */
    public boolean isNormal(Class<? extends Annotation> scopeAnnotation) {
        return normalScopes.contains(scopeAnnotation.getName());
    }

    @SuppressWarnings("unchecked")
    private static @org.jspecify.annotations.Nullable Class<? extends Annotation> scopeOf(
        AnnotationValue<io.micronaut.cdi.internal.metadata.CdiRegisteredContext> registered) {
        return (Class<? extends Annotation>) registered.classValue("scope").orElse(null);
    }

    /**
     * The contexts registered for the given scope, active or not.
     *
     * @param scopeAnnotation The scope
     * @return The contexts, empty when the scope is not an extension's
     */
    public List<AlterableContext> contextsFor(Class<? extends Annotation> scopeAnnotation) {
        return contextsByScope.getOrDefault(scopeAnnotation.getName(), List.of());
    }
}
