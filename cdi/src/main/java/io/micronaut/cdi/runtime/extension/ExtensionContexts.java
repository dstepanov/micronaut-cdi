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
 * <p>Which context class serves which scope was recorded on the scope annotation while it was compiled; here
 * the beans carrying the scope are read for that record, one instance of every context class is created, and a
 * Micronaut custom scope is registered for each scope so that resolution of a bean in it goes through the
 * context the extension provided.</p>
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
    private final Map<String, Class<? extends Annotation>> scopeAnnotations = new LinkedHashMap<>();

    public ExtensionContexts(BeanContext beanContext) {
        this.beanContext = beanContext;
    }

    @PostConstruct
    void standUp() {
        ClassLoader classLoader = beanContext.getClassLoader() != null
            ? beanContext.getClassLoader() : ExtensionContexts.class.getClassLoader();
        Set<String> seenScopes = new LinkedHashSet<>();
        for (BeanDefinition<?> definition : beanContext.getAllBeanDefinitions()) {
            List<String> qualifierNames = new ArrayList<>(definition.getAnnotationMetadata()
                .getAnnotationNamesByStereotype(io.micronaut.core.annotation.AnnotationUtil.QUALIFIER));
            qualifierNames.addAll(definition.getAnnotationMetadata()
                .getAnnotationNamesByStereotype("jakarta.inject.Qualifier"));
            for (String qualifierName : qualifierNames) {
                // an annotation an extension made a qualifier of does not say so on its own class: what the
                // compiled metadata knows, the runtime checks are told
                io.micronaut.cdi.runtime.ExtensionQualifiers.register(qualifierName);
            }
            AnnotationValue<io.micronaut.cdi.annotation.CdiExtensionQualifiers> qualifiers =
                definition.getAnnotationMetadata()
                    .getAnnotation(io.micronaut.cdi.annotation.CdiExtensionQualifiers.class);
            if (qualifiers != null) {
                for (String entry : qualifiers.stringValues()) {
                    String[] parts = entry.split("\\|", -1);
                    io.micronaut.cdi.runtime.ExtensionQualifiers.register(parts[0]);
                    if (parts.length > 1 && !parts[1].isEmpty()) {
                        for (String member : parts[1].split(";")) {
                            io.micronaut.cdi.runtime.ExtensionQualifiers
                                .registerNonbindingMember(parts[0], member);
                        }
                    }
                }
            }
            AnnotationValue<io.micronaut.cdi.annotation.CdiExtensionContextRecord> recorded =
                definition.getAnnotationMetadata()
                    .getAnnotation(io.micronaut.cdi.annotation.CdiExtensionContextRecord.class);
            if (recorded == null) {
                continue;
            }
            for (String entry : recorded.stringValues()) {
                String[] parts = entry.split("\\|");
                if (parts.length != 3) {
                    continue;
                }
                String scopeName = parts[0];
                if (!seenScopes.add(scopeName)) {
                    continue;
                }
                List<AlterableContext> contexts = new ArrayList<>();
                for (String contextClassName : parts[2].split(";")) {
                    contexts.add(instantiate(contextClassName, classLoader));
                }
                contextsByScope.put(scopeName, contexts);
                Class<? extends Annotation> scopeAnnotation = annotationClass(scopeName, classLoader);
                scopeAnnotations.put(scopeName, scopeAnnotation);
                beanContext.registerBeanDefinition(RuntimeBeanDefinition
                    .builder(io.micronaut.context.scope.CustomScope.class,
                        () -> new ExtensionCustomScope(scopeAnnotation, contexts, beanContext))
                    .singleton(true)
                    .typeArguments(Argument.of(scopeAnnotation))
                    .build());
            }
        }
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

    private static AlterableContext instantiate(String contextClassName, ClassLoader classLoader) {
        try {
            return (AlterableContext) Class.forName(contextClassName, true, classLoader)
                .getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("The context " + contextClassName + " could not be created", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Class<? extends Annotation> annotationClass(String name, ClassLoader classLoader) {
        try {
            return (Class<? extends Annotation>) Class.forName(name, false, classLoader);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("The scope annotation " + name + " could not be loaded", e);
        }
    }
}
