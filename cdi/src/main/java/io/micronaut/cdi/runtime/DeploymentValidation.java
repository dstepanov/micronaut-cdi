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
package io.micronaut.cdi.runtime;

import io.micronaut.cdi.runtime.type.SpecificationTypes;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.type.Argument;
import io.micronaut.inject.BeanDefinition;
import io.micronaut.inject.ExecutableMethod;
import io.micronaut.inject.FieldInjectionPoint;
import io.micronaut.inject.MethodInjectionPoint;
import jakarta.enterprise.inject.AmbiguousResolutionException;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.DeploymentException;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The validation of a deployment as the container starts: what the processor could not judge while one class
 * compiled, because it takes the whole deployment to judge.
 *
 * <p>Which bean an injection point resolves to cannot be known while one class compiles: the bean may be
 * compiled in another module, or be registered as the container starts. So the injection points of the beans of
 * the specification are walked here, from what the beans were compiled with, without creating anything. An
 * injection point that resolves to no bean, or to more than one that cannot be told apart, is a deployment
 * problem (CDI 4.1 section 5.2.2), and so is one that resolves to a bean in a normal scope that cannot be proxied
 * (sections 3.10 and 5.4). So are two beans of one name, and a name that is the path prefix of another
 * (section 5.3.1). The bean is not rejected on its own: one nothing is injected with is
 * refused only when a contextual reference to it is asked for. Every problem found is reported, the first as
 * the failure and the rest beside it.</p>
 *
 * <p>Only the beans of the specification are validated: a bean of Micronaut's own that shares the context is
 * resolved by Micronaut's rules. Kinds that resolve late by design - {@code Instance}, {@code Provider},
 * {@code Event}, {@code Optional}, the injection point itself - are left to their own lateness, and so are the
 * injection points Micronaut resolves by its own rules: a collection, which is the beans of its element type and
 * is empty rather than unsatisfied where there are none, and one marked nullable.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
final class DeploymentValidation {

    private static final Set<String> LAZY_KINDS = Set.of(
        "jakarta.enterprise.inject.Instance",
        "jakarta.inject.Provider",
        "io.micronaut.context.BeanProvider",
        "jakarta.enterprise.event.Event",
        "jakarta.enterprise.inject.spi.InjectionPoint",
        "jakarta.enterprise.inject.spi.EventMetadata",
        "java.util.Optional"
    );

    private final CdiBeanContainer container;
    private final List<DeploymentException> problems = new ArrayList<>();

    DeploymentValidation(CdiBeanContainer container) {
        this.container = container;
    }

    /**
     * Validates the beans the container knows.
     *
     * @throws DeploymentException Where the deployment is invalid
     */
    void validate() {
        List<CdiBean<?>> beans = container.beans();
        for (CdiBean<?> bean : beans) {
            BeanDefinition<?> definition = bean.definition();
            if (!isOfTheSpecification(definition)) {
                continue;
            }
            for (Argument<?> argument : definition.getConstructor().getArguments()) {
                validate(definition, argument, true);
            }
            for (FieldInjectionPoint<?, ?> field : definition.getInjectedFields()) {
                validate(definition, field.asArgument(), true);
            }
            for (MethodInjectionPoint<?, ?> method : definition.getInjectedMethods()) {
                if (method.isPostConstructMethod() || method.isPreDestroyMethod()) {
                    continue;
                }
                for (Argument<?> argument : method.getArguments()) {
                    validate(definition, argument, true);
                }
            }
            validateDisposer(definition, beans);
            validateObservers(definition);
        }
        validateNames(beans);
        if (!problems.isEmpty()) {
            DeploymentException failure = problems.get(0);
            for (int i = 1; i < problems.size(); i++) {
                failure.addSuppressed(problems.get(i));
            }
            throw failure;
        }
    }

    /**
     * The parameters of a disposer method other than the disposed one are injection points.
     */
    private void validateDisposer(BeanDefinition<?> definition, List<CdiBean<?>> beans) {
        AnnotationValue<?> disposer = definition.getAnnotationMetadata()
            .getAnnotation("io.micronaut.cdi.internal.metadata.CdiDisposer");
        if (disposer == null) {
            return;
        }
        String method = disposer.stringValue("method").orElse(null);
        String declaring = disposer.stringValue("declaringType").orElse(null);
        if (method == null || declaring == null) {
            return;
        }
        int disposed = disposer.intValue("disposedParameter").orElse(0);
        for (CdiBean<?> bean : beans) {
            BeanDefinition<?> declaringDefinition = bean.definition();
            if (!declaringDefinition.getBeanType().getName().equals(declaring)) {
                continue;
            }
            for (ExecutableMethod<?, ?> executable : declaringDefinition.getExecutableMethods()) {
                if (!executable.getMethodName().equals(method)) {
                    continue;
                }
                Argument<?>[] arguments = executable.getArguments();
                for (int i = 0; i < arguments.length; i++) {
                    if (i != disposed) {
                        validate(definition, arguments[i], false);
                    }
                }
                return;
            }
        }
    }

    /**
     * The parameters of an observer method other than the observed one are injection points.
     */
    private void validateObservers(BeanDefinition<?> definition) {
        for (ExecutableMethod<?, ?> method : definition.getExecutableMethods()) {
            AnnotationValue<?> observer = method.getAnnotationMetadata()
                .getAnnotation("io.micronaut.cdi.internal.metadata.CdiObserver");
            if (observer == null) {
                continue;
            }
            int observed = observer.intValue("observedParameter").orElse(0);
            Argument<?>[] arguments = method.getArguments();
            for (int i = 0; i < arguments.length; i++) {
                if (i != observed) {
                    validate(definition, arguments[i], false);
                }
            }
        }
    }

    /**
     * Whether the definition is a bean of the specification: one written with a scope of the specification, or
     * given one by default, rather than a bean of Micronaut's own that only shares the context.
     */
    private static boolean isOfTheSpecification(BeanDefinition<?> definition) {
        return definition.getAnnotationMetadata().hasAnnotation("io.micronaut.cdi.internal.metadata.CdiScope")
            && !definition.getAnnotationMetadata().hasAnnotation("jakarta.interceptor.Interceptor");
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

    /**
     * Whether Micronaut injects the beans of the element type at the injection point rather than a bean of its
     * type: a collection, a stream or a map. An array is resolved as a bean of the array type.
     */
    private static boolean isCollectedByMicronaut(Argument<?> argument) {
        Class<?> type = argument.getType();
        return Iterable.class.isAssignableFrom(type)
            || java.util.stream.Stream.class.isAssignableFrom(type)
            || java.util.Map.class.isAssignableFrom(type);
    }

    /**
     * Section 5.3.1: a name that resolves to more than one bean once the alternatives have had their say, or a
     * name of the form {@code x.y} where {@code x} is the name of another bean, is a deployment problem.
     */
    private void validateNames(List<CdiBean<?>> beans) {
        java.util.Map<String, List<BeanDefinition<?>>> names = new java.util.LinkedHashMap<>();
        for (CdiBean<?> bean : beans) {
            String name = bean.getName();
            if (name != null && !name.isEmpty() && isOfTheSpecification(bean.definition())) {
                names.computeIfAbsent(name, key -> new ArrayList<>()).add(bean.definition());
            }
        }
        for (java.util.Map.Entry<String, List<BeanDefinition<?>>> entry : names.entrySet()) {
            List<BeanDefinition<?>> narrowed = CdiResolution.narrow(entry.getValue());
            if (narrowed.size() > 1) {
                problems.add(new DeploymentException("The name " + entry.getKey()
                    + " resolves to more than one bean: " + narrowed + " (CDI 4.1 section 5.3.1)"));
            }
        }
        for (String name : names.keySet()) {
            for (String other : names.keySet()) {
                if (other.startsWith(name + ".")) {
                    problems.add(new DeploymentException("The name " + name + " is a path prefix of the name "
                        + other + " (CDI 4.1 section 5.3.1)"));
                }
            }
        }
    }

    /**
     * Resolves the injection point the way the container resolves it, and reports the bean it resolves to
     * where that bean is in a normal scope and cannot be proxied.
     */
    private void validate(BeanDefinition<?> definition, Argument<?> argument, boolean injectedByMicronaut) {
        if (LAZY_KINDS.contains(argument.getType().getName())
            || isContainerMachinery(argument)
            || argument.getName().startsWith("$")) {
            return;
        }
        if (argument.isNullable() || injectedByMicronaut && isCollectedByMicronaut(argument)) {
            // an injection point Micronaut resolves by its own rules: an optional one, and a collection that
            // Micronaut injects - into a field, a constructor or an initializer - which is the beans of its
            // element type and is empty rather than unsatisfied where there are none. The parameter of an
            // observer or a disposer method is resolved by the container as a bean of its type
            return;
        }
        List<CdiQualifier> qualifiers = CdiQualifier.declared(argument.getAnnotationMetadata());
        Set<Bean<?>> beans;
        try {
            beans = container.beansOf(SpecificationTypes.argumentOf(CdiTypes.requiredTypeOf(argument)), qualifiers);
        } catch (IllegalArgumentException e) {
            // a type with a variable the declaring class gives a value the definition does not carry: resolved
            // as the bean is created
            return;
        }
        // an interceptor is a bean of its class, but is never injected
        beans.removeIf(bean -> bean instanceof CdiBean<?> cdiBean && cdiBean.definition()
            .getAnnotationMetadata().hasAnnotation("jakarta.interceptor.Interceptor"));
        Bean<?> resolved;
        try {
            resolved = container.resolve(beans);
        } catch (AmbiguousResolutionException e) {
            problems.add(new DeploymentException("The injection point " + argument + " of "
                + definition.getBeanType().getName() + " is ambiguous (CDI 4.1 section 5.2.2): "
                + e.getMessage(), e));
            return;
        }
        if (resolved == null) {
            problems.add(new DeploymentException("The injection point " + argument + " of "
                + definition.getBeanType().getName() + " has no bean to satisfy it, qualified " + qualifiers
                + " (CDI 4.1 section 5.2.2)"));
            return;
        }
        if (resolved instanceof CdiBean<?> bean) {
            String unproxyable = bean.definition().getAnnotationMetadata()
                .stringValue("io.micronaut.cdi.internal.metadata.CdiUnproxyable").orElse(null);
            if (unproxyable != null) {
                problems.add(new DeploymentException("The injection point " + argument + " of "
                    + definition.getBeanType().getName() + " resolves to a bean that cannot be proxied: "
                    + unproxyable + " (CDI 4.1 section 5.4)"));
            }
        }
    }
}
