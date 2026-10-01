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
 * the specification are walked here, from what the beans were compiled with, without creating anything, and an
 * injection point that resolves to a bean in a normal scope that cannot be proxied is a deployment problem
 * (CDI 4.1 sections 3.10 and 5.4). The bean is not rejected on its own: one nothing is injected with is
 * refused only when a contextual reference to it is asked for. Every problem found is reported, the first as
 * the failure and the rest beside it.</p>
 *
 * <p>Only the beans of the specification are validated: a bean of Micronaut's own that shares the context is
 * resolved by Micronaut's rules. Kinds that resolve late by design - {@code Instance}, {@code Provider},
 * {@code Event}, {@code Optional}, the injection point itself - are left to their own lateness.</p>
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
                validate(definition, argument);
            }
            for (FieldInjectionPoint<?, ?> field : definition.getInjectedFields()) {
                validate(definition, field.asArgument());
            }
            for (MethodInjectionPoint<?, ?> method : definition.getInjectedMethods()) {
                if (method.isPostConstructMethod() || method.isPreDestroyMethod()) {
                    continue;
                }
                for (Argument<?> argument : method.getArguments()) {
                    validate(definition, argument);
                }
            }
            validateDisposer(definition, beans);
            validateObservers(definition);
        }
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
            .getAnnotation("io.micronaut.cdi.annotation.CdiDisposer");
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
                        validate(definition, arguments[i]);
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
                .getAnnotation("io.micronaut.cdi.annotation.CdiObserver");
            if (observer == null) {
                continue;
            }
            int observed = observer.intValue("observedParameter").orElse(0);
            Argument<?>[] arguments = method.getArguments();
            for (int i = 0; i < arguments.length; i++) {
                if (i != observed) {
                    validate(definition, arguments[i]);
                }
            }
        }
    }

    /**
     * Whether the definition is a bean of the specification: one written with a scope of the specification, or
     * given one by default, rather than a bean of Micronaut's own that only shares the context.
     */
    private static boolean isOfTheSpecification(BeanDefinition<?> definition) {
        return definition.getAnnotationMetadata().hasAnnotation("io.micronaut.cdi.annotation.CdiScope")
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
     * Resolves the injection point the way the container resolves it, and reports the bean it resolves to
     * where that bean is in a normal scope and cannot be proxied.
     */
    private void validate(BeanDefinition<?> definition, Argument<?> argument) {
        if (LAZY_KINDS.contains(argument.getType().getName())
            || isContainerMachinery(argument)
            || argument.getName().startsWith("$")) {
            return;
        }
        Bean<?> resolved;
        try {
            Set<Bean<?>> beans = container.beansOf(
                SpecificationTypes.argumentOf(CdiTypes.requiredTypeOf(argument)),
                CdiQualifier.declared(argument.getAnnotationMetadata()));
            // an interceptor is a bean of its class, but is never injected
            beans.removeIf(bean -> bean instanceof CdiBean<?> cdiBean && cdiBean.definition()
                .getAnnotationMetadata().hasAnnotation("jakarta.interceptor.Interceptor"));
            resolved = container.resolve(beans);
        } catch (AmbiguousResolutionException | IllegalArgumentException e) {
            // an injection point that resolves to no one bean, or whose type has a variable the declaring class
            // gives a value the definition does not carry, is left to resolve as the bean is created
            return;
        }
        if (resolved instanceof CdiBean<?> bean) {
            String unproxyable = bean.definition().getAnnotationMetadata()
                .stringValue("io.micronaut.cdi.annotation.CdiUnproxyable").orElse(null);
            if (unproxyable != null) {
                problems.add(new DeploymentException("The injection point " + argument + " of "
                    + definition.getBeanType().getName() + " resolves to a bean that cannot be proxied: "
                    + unproxyable + " (CDI 4.1 section 5.4)"));
            }
        }
    }
}
