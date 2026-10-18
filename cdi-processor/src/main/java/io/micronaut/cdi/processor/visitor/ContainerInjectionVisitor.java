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
package io.micronaut.cdi.processor.visitor;

import io.micronaut.cdi.processor.Cdi;
import io.micronaut.context.annotation.ResolveWith;
import io.micronaut.core.annotation.AnnotationClassValue;
import io.micronaut.core.annotation.Internal;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.Element;
import io.micronaut.inject.ast.ElementQuery;
import io.micronaut.inject.ast.MethodElement;
import io.micronaut.inject.ast.ParameterElement;
import io.micronaut.inject.visitor.TypeElementVisitor;
import io.micronaut.inject.visitor.VisitorContext;
import io.micronaut.inject.utils.BeanInjectionUtils;

import java.util.Set;

/**
 * Selects the container's resolution for every injection point of a bean of the specification - a field, a
 * parameter of its constructor, of an initializer, of a producer, of a disposer and of an observer - through
 * Micronaut's {@code ResolveWith}, so that a point is resolved among the beans the container selects for a lookup
 * and the bean manager as well, the full declared type of a container type - a list, an optional, an array - among
 * them, before Micronaut generates its aggregation. What Micronaut resolves itself is left to it. Only user members
 * are annotated; generated proxy infrastructure retains its own resolution, and so does a bean of Micronaut's own.
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class ContainerInjectionVisitor implements TypeElementVisitor<Object, Object> {

    /**
     * The kinds whose beans are built in and read the point they are injected into, resolved as late as the point
     * is used.
     */
    private static final Set<String> LATE_KINDS = Set.of(
        "jakarta.enterprise.inject.Instance",
        "jakarta.inject.Provider",
        "io.micronaut.context.BeanProvider",
        "jakarta.enterprise.event.Event",
        "jakarta.enterprise.inject.spi.InjectionPoint",
        "jakarta.enterprise.inject.spi.EventMetadata",
        "io.micronaut.cdi.MicronautInstance",
        "io.micronaut.cdi.MicronautEvent",
        "io.micronaut.cdi.MicronautInjectionPoint"
    );

    /**
     * The types Micronaut resolves without a bean definition: the interfaces it hands the context itself out as -
     * the private {@code thisInterfaces} of {@code DefaultBeanContext}, which Micronaut does not publish - and the
     * dependency resolver and the interception target it makes for the bean being resolved. A marked point no bean
     * of the specification answers is left to Micronaut as it is injected, which would resolve these too; but the
     * deployment validation asks Micronaut for a definition of what such a point resolves to, which none of these
     * has, and a bean of the specification that implements one of them would take the context's place.
     */
    private static final Set<String> CONTEXT_TYPES = Set.of(
        "io.micronaut.context.BeanDefinitionRegistry",
        "io.micronaut.context.BeanContext",
        "io.micronaut.context.WatchableBeanContext",
        "io.micronaut.core.annotation.AnnotationMetadataResolver",
        "io.micronaut.context.BeanLocator",
        "io.micronaut.context.ExecutionHandleLocator",
        "io.micronaut.context.ApplicationContext",
        "io.micronaut.core.value.PropertyResolver",
        "io.micronaut.core.value.ValueResolver",
        "io.micronaut.context.env.PropertyPlaceholderResolver",
        "io.micronaut.context.BeanDependencyResolver",
        "io.micronaut.context.InterceptionTarget"
    );

    @Override
    public io.micronaut.inject.visitor.TypeElementQuery query() {
        // only the class is visited: what it declares is read from it, and core need not walk its members
        return io.micronaut.inject.visitor.TypeElementQuery.onlyClass();
    }

    @Override
    public VisitorKind getVisitorKind() {
        return VisitorKind.ISOLATING;
    }

    @Override
    public int getOrder() {
        return LOWEST_PRECEDENCE - 250;
    }

    @Override
    public void visitClass(ClassElement bean, VisitorContext context) {
        if (!SpecificationBeans.isBeanOfTheSpecification(bean)) {
            return;
        }
        // what the class declares and what it inherits (section 4.2): since Micronaut Core 5.3 a mark written on an
        // inherited field or parameter while the class is visited is kept by this class, and a bean of Micronaut's
        // own that inherits the same member keeps Micronaut's resolution
        bean.getEnclosedElements(ElementQuery.ALL_FIELDS).stream()
            .filter(ContainerInjectionVisitor::isInjected)
            .forEach(field -> mark(field, field.getGenericType()));
        bean.getEnclosedElements(ElementQuery.ALL_FIELDS).stream()
            .filter(field -> isInjected(field)
                && field.getModifiers().contains(io.micronaut.inject.ast.ElementModifier.TRANSIENT))
            // InjectionPoint.isTransient() answers from the record rather than from the field itself; the record is
            // the same whatever class inherits the field, so it is written of an inherited one as well
            .forEach(field -> field.annotate(io.micronaut.cdi.internal.metadata.CdiTransient.class));
        for (io.micronaut.inject.ast.PropertyElement property : bean.getSyntheticBeanProperties()) {
            // a property a compiler describes as one - a Kotlin property under KSP - is injected through its setter,
            // with the metadata of the property; one whose setter alone is annotated, @set:Inject, as well
            MethodElement setter = property.getWriteMethod().orElse(null);
            if (setter != null && (isInjected(property) || isInjected(setter))) {
                // the declaration is the same whatever class inherits the setter
                recordDeclaration(setter);
                markParameters(setter);
            }
        }
        BeanInjectionUtils.findBeanConstructor(bean).ifPresent(ContainerInjectionVisitor::markParameters);
        for (MethodElement method : bean.getEnclosedElements(ElementQuery.ALL_METHODS)) {
            if (isInjected(method)) {
                // the same whatever class inherits the method, so it is written of an inherited one as well
                recordDeclaration(method);
            }
            // an initializer and an observer are inherited (section 4.2), a producer and a disposer are the beans
            // of the class declaring them
            boolean declared = method.getDeclaringType().getName().equals(bean.getName());
            if (isInjected(method)
                || (declared && (method.hasAnnotation(Cdi.PRODUCES) || hasParameterAnnotated(method, Cdi.DISPOSES)))
                || hasParameterAnnotated(method, Cdi.OBSERVES) || hasParameterAnnotated(method, Cdi.OBSERVES_ASYNC)) {
                markParameters(method);
            }
        }
    }

    /**
     * Records the class that declares an injected method and the erasure of the parameter types it declares,
     * which the types the bean's definition compiles an inherited method with need not be.
     */
    private static void recordDeclaration(MethodElement method) {
        ParameterElement[] parameters = method.getParameters();
        String[] types = new String[parameters.length];
        for (int i = 0; i < parameters.length; i++) {
            types[i] = RecordedTypeValues.erasedName(parameters[i].getType());
        }
        method.annotate(io.micronaut.cdi.internal.metadata.CdiInjectedMethod.class, builder -> builder
            .member("declaringType", new AnnotationClassValue<>(method.getDeclaringType().getName()))
            .member("parameterTypes", types));
    }

    private static void markParameters(MethodElement method) {
        for (ParameterElement parameter : method.getParameters()) {
            if (!parameter.hasAnnotation(Cdi.OBSERVES) && !parameter.hasAnnotation(Cdi.OBSERVES_ASYNC)
                && !parameter.hasAnnotation(Cdi.DISPOSES)) {
                mark(parameter, parameter.getGenericType());
            }
        }
    }

    private static boolean hasParameterAnnotated(MethodElement method, String annotation) {
        for (ParameterElement parameter : method.getParameters()) {
            if (parameter.hasAnnotation(annotation)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isInjected(Element element) {
        return SpecificationBeans.isInjected(element);
    }

    private static void mark(Element element, ClassElement type) {
        if (element.hasStereotype(ResolveWith.class) || isResolvedByMicronaut(element, type)) {
            return;
        }
        element.annotate(ResolveWith.class, builder -> builder.member("value", new AnnotationClassValue<>(
            "io.micronaut.cdi.internal.runtime.CdiBeanInjectionProvider")));
    }

    /**
     * Whether Micronaut resolves the point itself rather than by a bean of its type: a value of the configuration,
     * a registration, the context itself, its own injection point, the qualifier the bean is resolved by, or one of
     * the kinds resolved late - a lookup, an event, the injection
     * point metadata - whose beans are built-in ones that read the point they are injected into.
     */
    private static boolean isResolvedByMicronaut(Element element, ClassElement type) {
        if (element.hasStereotype(io.micronaut.context.annotation.Value.class)
            || element.hasStereotype(io.micronaut.context.annotation.Property.class)
            || element.hasStereotype(io.micronaut.context.annotation.Parameter.class)) {
            return true;
        }
        ClassElement resolved = type.isArray() ? type.fromArray() : type;
        if (resolved.isAssignable(io.micronaut.context.BeanRegistration.class)
            || type.getFirstTypeArgument().map(argument -> argument.isAssignable(io.micronaut.context.BeanRegistration.class))
                .orElse(false)) {
            return true;
        }
        // Micronaut injects its InjectionPoint from the resolution under way, and its Qualifier as the qualifier the
        // bean is resolved by, without a bean definition of either
        return LATE_KINDS.contains(type.getName()) || CONTEXT_TYPES.contains(type.getName())
            || type.isAssignable("io.micronaut.inject.InjectionPoint")
            || type.isAssignable("io.micronaut.context.Qualifier");
    }
}
