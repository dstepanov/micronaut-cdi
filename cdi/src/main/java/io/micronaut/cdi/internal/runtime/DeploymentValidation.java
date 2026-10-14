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
package io.micronaut.cdi.internal.runtime;

import io.micronaut.cdi.internal.metadata.CdiBuiltIn;
import io.micronaut.cdi.internal.metadata.CdiDisposer;
import io.micronaut.cdi.internal.metadata.CdiObserver;
import io.micronaut.cdi.internal.metadata.CdiUnproxyable;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.type.Argument;
import io.micronaut.inject.BeanDefinition;
import io.micronaut.inject.ExecutableMethod;
import io.micronaut.inject.FieldInjectionPoint;
import io.micronaut.inject.MethodInjectionPoint;
import jakarta.enterprise.inject.AmbiguousResolutionException;
import jakarta.enterprise.inject.spi.DeploymentException;

import java.util.ArrayList;
import java.util.List;

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
 * {@code Event}, the injection point itself - are left to their own lateness, and so are injection points
 * marked nullable. So are the types Micronaut resolves without a bean definition: the context itself, under each
 * interface it is handed out as, and Micronaut's own injection point. Container injection points require a bean of their full declared type, just like other
 * CDI injection points.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
final class DeploymentValidation {

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
            boolean proxy = CdiResolution.isProxyDefinition(definition);
            for (Argument<?> argument : definition.getConstructor().getArguments()) {
                // what the container itself hands the constructor of a generated subclass is no injection point
                if (!proxy || !CdiResolution.isGeneratedConstructorParameter(argument)) {
                    validate(definition, argument);
                }
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
            validateDisposer(definition);
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
    private void validateDisposer(BeanDefinition<?> definition) {
        AnnotationValue<CdiDisposer> recorded = definition.getAnnotationMetadata().getAnnotation(CdiDisposer.class);
        if (recorded == null) {
            return;
        }
        DisposerInvoker.Disposer disposer;
        try {
            // the disposer the product is destroyed by, found the way it is found then
            disposer = DisposerInvoker.disposerOf(container.beanContext(), recorded);
        } catch (IllegalStateException e) {
            problems.add(new DeploymentException(e.getMessage(), e));
            return;
        } catch (io.micronaut.context.exceptions.NonUniqueBeanException e) {
            // a subclass of the one below, and a problem rather than the absence of the declaring bean
            problems.add(new DeploymentException("The bean that declares the disposer method of "
                + definition.getBeanType().getName() + " is not one bean: " + e.getMessage(), e));
            return;
        } catch (io.micronaut.context.exceptions.NoSuchBeanException e) {
            // the bean that declares it is not one of the deployment, and neither is what it produces
            return;
        }
        if (disposer == null) {
            return;
        }
        Argument<?>[] arguments = disposer.method().getArguments();
        for (int i = 0; i < arguments.length; i++) {
            if (i != disposer.disposedParameter()) {
                validate(definition, arguments[i]);
            }
        }
    }

    /**
     * The parameters of an observer method other than the observed one are injection points.
     */
    private void validateObservers(BeanDefinition<?> definition) {
        for (ExecutableMethod<?, ?> method : definition.getExecutableMethods()) {
            AnnotationValue<?> observer = method.getAnnotationMetadata()
                .getAnnotation(CdiObserver.class);
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
        // a built-in bean, such as the bean manager, is handed the context it belongs to rather than injected by
        // the rules of the specification
        return CdiResolution.isBeanOfTheSpecification(definition)
            && !definition.hasAnnotation(CdiBuiltIn.class)
            && !definition.getAnnotationMetadata().hasAnnotation("jakarta.interceptor.Interceptor");
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
     * Resolves a point no bean of the specification is a candidate for the way Micronaut resolves it then, by its
     * own rules among the beans of Micronaut's own, and reports it where they find no bean or more than one.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void validateByMicronaut(BeanDefinition<?> definition, Argument<?> argument) {
        try {
            if (container.beanContext().findBeanDefinition((Argument) argument,
                CdiBeanContainer.Selection.micronautQualifier((Argument) argument, null)).isEmpty()) {
                problems.add(new DeploymentException("The injection point " + argument + " of "
                    + definition.getBeanType().getName() + " has no bean to satisfy it (CDI 4.1 section 5.2.2)"));
            }
        } catch (io.micronaut.context.exceptions.NonUniqueBeanException e) {
            problems.add(new DeploymentException("The injection point " + argument + " of "
                + definition.getBeanType().getName() + " is ambiguous (CDI 4.1 section 5.2.2): "
                + e.getMessage(), e));
        }
    }

    /**
     * Resolves the injection point the way the container resolves it, and reports the bean it resolves to
     * where that bean is in a normal scope and cannot be proxied.
     */
    private void validate(BeanDefinition<?> definition, Argument<?> argument) {
        if (!argument.getAnnotationMetadata().hasStereotype(io.micronaut.context.annotation.ResolveWith.class)) {
            // a point Micronaut resolves itself, which the processor left unmarked: a lookup, an event, the
            // injection point metadata, the context, a value of the configuration
            return;
        }
        if (argument.isNullable()) {
            // An explicitly nullable injection point may have no bean.
            return;
        }
        // what the point is resolved to as the bean is created
        CdiBeanContainer.Selection selection;
        try {
            selection = container.selectionFor(argument);
        } catch (AmbiguousResolutionException e) {
            problems.add(new DeploymentException("The injection point " + argument + " of "
                + definition.getBeanType().getName() + " is ambiguous (CDI 4.1 section 5.2.2): "
                + e.getMessage(), e));
            return;
        }
        if (selection == CdiBeanContainer.Selection.MICRONAUT) {
            validateByMicronaut(definition, argument);
            return;
        }
        BeanDefinition<?> resolved = selection.definition();
        if (resolved == null) {
            problems.add(new DeploymentException("The injection point " + argument + " of "
                + definition.getBeanType().getName() + " has no bean to satisfy it, qualified "
                + CdiQualifier.declared(argument.getAnnotationMetadata()) + " (CDI 4.1 section 5.2.2)"));
            return;
        }
        String unproxyable = resolved.getAnnotationMetadata().stringValue(CdiUnproxyable.class).orElse(null);
        if (unproxyable != null) {
            problems.add(new DeploymentException("The injection point " + argument + " of "
                + definition.getBeanType().getName() + " resolves to a bean that cannot be proxied: "
                + unproxyable + " (CDI 4.1 section 5.4)"));
        }
    }
}
