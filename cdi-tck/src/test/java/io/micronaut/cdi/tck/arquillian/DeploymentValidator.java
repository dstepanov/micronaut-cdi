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
package io.micronaut.cdi.tck.arquillian;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.Qualifier;
import io.micronaut.context.exceptions.NonUniqueBeanException;
import io.micronaut.core.type.Argument;
import io.micronaut.inject.BeanDefinition;
import io.micronaut.inject.FieldInjectionPoint;
import io.micronaut.inject.MethodInjectionPoint;
import io.micronaut.inject.qualifiers.Qualifiers;
import jakarta.enterprise.inject.spi.DeploymentException;

import java.util.Set;

/**
 * The validation a deployment gets before its tests run: every injection point of its beans resolves, to
 * exactly one bean.
 *
 * <p>The specification has the container detect an unsatisfied or ambiguous dependency as it deploys, and
 * report it as a deployment problem. Micronaut resolves lazily — a dependent bean is created when it is asked
 * for — so deploying alone would not walk the dependencies; this walk asks the resolution question for each
 * injection point without creating anything. Kinds that resolve late by design — {@code Instance},
 * {@code Provider}, {@code Event}, {@code Optional}, the injection point itself — are left to their own
 * lateness, exactly because the specification does not require them satisfied at deployment.</p>
 */
final class DeploymentValidator {

    private static final Set<String> LAZY_KINDS = Set.of(
        "jakarta.enterprise.inject.Instance",
        "jakarta.inject.Provider",
        "io.micronaut.context.BeanProvider",
        "jakarta.enterprise.event.Event",
        "jakarta.enterprise.inject.spi.InjectionPoint",
        "java.util.Optional"
    );

    private DeploymentValidator() {
    }

    /**
     * Walks the injection points of the deployment's own beans.
     *
     * @param context The started container
     * @param archiveClasses The outer class names of the archive
     */
    static void validate(ApplicationContext context, Set<String> archiveClasses) {
        java.util.Map<String, java.util.List<BeanDefinition<?>>> names = new java.util.HashMap<>();
        for (BeanDefinition<?> definition : context.getAllBeanDefinitions()) {
            if (!belongsToArchive(definition, archiveClasses)) {
                continue;
            }
            String name = nameOf(definition);
            if (name != null) {
                names.computeIfAbsent(name, key -> new java.util.ArrayList<>()).add(definition);
            }
            for (Argument<?> argument : definition.getConstructor().getArguments()) {
                requireResolvable(context, definition, argument);
            }
            for (FieldInjectionPoint<?, ?> field : definition.getInjectedFields()) {
                requireResolvable(context, definition, field.asArgument());
            }
            for (MethodInjectionPoint<?, ?> method : definition.getInjectedMethods()) {
                if (method.isPostConstructMethod() || method.isPreDestroyMethod()) {
                    continue;
                }
                for (Argument<?> argument : method.getArguments()) {
                    requireResolvable(context, definition, argument);
                }
            }
            io.micronaut.core.annotation.AnnotationValue<?> disposerRecord = definition.getAnnotationMetadata()
                .getAnnotation("io.micronaut.cdi.annotation.CdiDisposer");
            if (disposerRecord != null) {
                // the other parameters of a disposer method are injection points, satisfied at deployment
                // like any other (section 3.3.7)
                String disposerName = disposerRecord.stringValue("method").orElse(null);
                int disposed = disposerRecord.intValue("disposedParameter").orElse(0);
                Class<?> declaring = disposerRecord.classValue("declaringType").orElse(null);
                if (disposerName != null && declaring != null) {
                    for (BeanDefinition<?> declaringDefinition : context.getBeanDefinitions(declaring)) {
                        declaringDefinition.getExecutableMethods().stream()
                            .filter(m -> m.getMethodName().equals(disposerName))
                            .findFirst()
                            .ifPresent(m -> {
                                Argument<?>[] arguments = m.getArguments();
                                for (int i = 0; i < arguments.length; i++) {
                                    if (i != disposed) {
                                        requireResolvable(context, definition, arguments[i]);
                                    }
                                }
                            });
                    }
                }
            }
            for (io.micronaut.inject.ExecutableMethod<?, ?> method : definition.getExecutableMethods()) {
                io.micronaut.core.annotation.AnnotationValue<?> observer = method.getAnnotationMetadata()
                    .getAnnotation("io.micronaut.cdi.annotation.CdiObserver");
                if (observer == null) {
                    continue;
                }
                // the other parameters of an observer method are injection points, satisfied at deployment
                // like any other
                int observed = observer.intValue("observedParameter").orElse(0);
                Argument<?>[] arguments = method.getArguments();
                for (int i = 0; i < arguments.length; i++) {
                    if (i != observed
                        && !"jakarta.enterprise.inject.spi.EventMetadata".equals(arguments[i].getType().getName())) {
                        requireResolvable(context, definition, arguments[i]);
                    }
                }
            }
        }
        requireDistinctNames(names);
    }

    private static @org.jspecify.annotations.Nullable String nameOf(BeanDefinition<?> definition) {
        String name = definition.getAnnotationMetadata().stringValue("io.micronaut.cdi.annotation.CdiName")
            .or(() -> definition.getAnnotationMetadata().stringValue("jakarta.inject.Named"))
            .or(() -> definition.getAnnotationMetadata()
                .stringValue(io.micronaut.core.annotation.AnnotationUtil.NAMED))
            .orElse(null);
        return name == null || name.isEmpty() ? null : name;
    }

    /**
     * Sections 2.6.2 and 2.6.3: a name that resolves to more than one bean once the alternatives have had
     * their say, or a name that is a path-prefix of another bean's name, is a deployment problem.
     */
    private static void requireDistinctNames(java.util.Map<String, java.util.List<BeanDefinition<?>>> names) {
        for (java.util.Map.Entry<String, java.util.List<BeanDefinition<?>>> entry : names.entrySet()) {
            java.util.List<BeanDefinition<?>> narrowed =
                io.micronaut.cdi.runtime.CdiResolution.narrow(entry.getValue());
            if (narrowed.size() > 1) {
                throw new DeploymentException("The name " + entry.getKey() + " resolves to more than one bean: "
                    + narrowed);
            }
        }
        for (String name : names.keySet()) {
            for (String other : names.keySet()) {
                if (!name.equals(other) && other.startsWith(name + ".")) {
                    throw new DeploymentException("The name " + name + " is a path prefix of the name " + other);
                }
            }
        }
    }

    /**
     * Whether the argument is something the container hands a generated constructor rather than an injection
     * point the author wrote: its type, or a type inside it, belongs to the container.
     */
    private static boolean isContainerMachinery(Argument<?> argument) {
        if (argument.getType().getName().startsWith("io.micronaut.")) {
            return true;
        }
        for (Argument<?> parameter : argument.getTypeParameters()) {
            if (isContainerMachinery(parameter)) {
                return true;
            }
        }
        return false;
    }

    private static boolean belongsToArchive(BeanDefinition<?> definition, Set<String> archiveClasses) {
        String name = definition.getBeanType().getName();
        if (!name.startsWith("org.jboss.cdi.tck.")) {
            return false;
        }
        int inner = name.indexOf('$');
        return archiveClasses.contains(inner < 0 ? name : name.substring(0, inner));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void requireResolvable(ApplicationContext context, BeanDefinition<?> definition,
                                          Argument<?> argument) {
        if (LAZY_KINDS.contains(argument.getType().getName())
            || isContainerMachinery(argument)
            || argument.getName().startsWith("$")
            || argument.isNullable()
            || argument.getAnnotationMetadata().hasAnnotation("jakarta.inject.Named")
                && argument.getType().equals(String.class)) {
            return;
        }
        // the resolution question is asked exactly the way the container answers it at runtime: the beans
        // eligible by the rules of section 2.4, resolved to one the way section 2.4.2 resolves them
        io.micronaut.cdi.runtime.CdiBeanContainer container =
            context.getBean(io.micronaut.cdi.runtime.CdiBeanContainer.class);
        java.util.Set<java.lang.annotation.Annotation> qualifiers =
            io.micronaut.cdi.runtime.CdiQualifiers.declared(argument.getAnnotationMetadata());
        java.lang.reflect.Type requiredType = io.micronaut.cdi.runtime.CdiTypes.requiredTypeOf(argument);
        java.util.Set<jakarta.enterprise.inject.spi.Bean<?>> beans = container.getBeans(
            requiredType, qualifiers.toArray(new java.lang.annotation.Annotation[0]));
        beans.removeIf(bean -> bean instanceof io.micronaut.cdi.runtime.CdiBean<?> cdiBean
            && cdiBean.definition().getAnnotationMetadata().hasAnnotation("jakarta.interceptor.Interceptor"));
        try {
            jakarta.enterprise.inject.spi.Bean<?> resolved = container.resolve(beans);
            if (resolved == null) {
                throw new DeploymentException("The injection point " + argument + " of "
                    + definition.getBeanType().getName() + " has no bean to satisfy it. Asked with "
                    + qualifiers + "; the beans of the type are "
                    + container.getBeans(requiredType, new jakarta.enterprise.inject.Any.Literal()));
            }
            if (resolved instanceof io.micronaut.cdi.runtime.CdiBean<?> cdiBean) {
                String unproxyable = cdiBean.definition().getAnnotationMetadata()
                    .stringValue("io.micronaut.cdi.annotation.CdiUnproxyable").orElse(null);
                if (unproxyable != null) {
                    // section 3.11: an injection point that resolves to an unproxyable bean in a normal scope
                    // is a deployment problem
                    throw new DeploymentException("The injection point " + argument + " of "
                        + definition.getBeanType().getName() + " resolves to a bean that cannot be proxied: "
                        + unproxyable);
                }
            }
        } catch (jakarta.enterprise.inject.AmbiguousResolutionException e) {
            throw new DeploymentException("The injection point " + argument + " of "
                + definition.getBeanType().getName() + " is ambiguous", e);
        }
    }
}
