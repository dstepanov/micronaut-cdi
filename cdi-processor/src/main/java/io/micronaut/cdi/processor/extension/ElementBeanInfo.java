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
package io.micronaut.cdi.processor.extension;

import io.micronaut.cdi.annotation.CdiScope;
import io.micronaut.cdi.processor.Cdi;
import io.micronaut.core.annotation.Internal;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.Element;
import io.micronaut.inject.ast.FieldElement;
import io.micronaut.inject.ast.MemberElement;
import io.micronaut.inject.ast.MethodElement;
import jakarta.enterprise.inject.build.compatible.spi.InterceptorInfo;
import jakarta.enterprise.inject.build.compatible.spi.DisposerInfo;
import jakarta.enterprise.inject.build.compatible.spi.InjectionPointInfo;
import jakarta.enterprise.inject.build.compatible.spi.ScopeInfo;
import jakarta.enterprise.inject.build.compatible.spi.StereotypeInfo;
import jakarta.enterprise.lang.model.AnnotationInfo;
import jakarta.enterprise.lang.model.declarations.ClassInfo;
import jakarta.enterprise.lang.model.declarations.FieldInfo;
import jakarta.enterprise.lang.model.declarations.MethodInfo;
import jakarta.enterprise.lang.model.types.Type;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * One bean, described to an extension while the class that declares it is compiled.
 *
 * <p>The registration phase of section 2.10.4 is invoked once for each bean whose types the extension asked for,
 * which is a description of one bean at a time rather than of the whole container. That is what a compiler can
 * give: the bean is the class in front of it, or a producer that class declares, and everything the phase reports
 * is read off the same element the rest of this module reads.</p>
 *
 * <p>It is therefore described without reading a class back at runtime, which is the whole point of compiling a
 * container: what a bean is was decided here, and this says what was decided.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class ElementBeanInfo implements InterceptorInfo {

    private final ClassElement declaringClass;
    private final @Nullable MemberElement producer;

    ElementBeanInfo(ClassElement declaringClass, @Nullable MemberElement producer) {
        this.declaringClass = declaringClass;
        this.producer = producer;
    }

    @Override
    public boolean isInterceptor() {
        return producer == null && declaringClass.hasDeclaredAnnotation("jakarta.interceptor.Interceptor");
    }

    @Override
    public java.util.Collection<jakarta.enterprise.lang.model.AnnotationInfo> interceptorBindings() {
        java.util.List<jakarta.enterprise.lang.model.AnnotationInfo> bindings = new java.util.ArrayList<>();
        for (String name : declaringClass.getAnnotationMetadata()
            .getAnnotationNamesByStereotype("jakarta.interceptor.InterceptorBinding")) {
            io.micronaut.core.annotation.AnnotationValue<?> value =
                declaringClass.getAnnotationMetadata().getAnnotation(name);
            if (value != null) {
                bindings.add(new ElementAnnotationInfo(value));
            }
        }
        return bindings;
    }

    @Override
    public boolean intercepts(jakarta.enterprise.inject.spi.InterceptionType interceptionType) {
        String marker = switch (interceptionType) {
            case AROUND_INVOKE -> "jakarta.interceptor.AroundInvoke";
            case AROUND_CONSTRUCT -> "jakarta.interceptor.AroundConstruct";
            case POST_CONSTRUCT -> "jakarta.annotation.PostConstruct";
            case PRE_DESTROY -> "jakarta.annotation.PreDestroy";
            default -> null;
        };
        if (marker == null) {
            return false;
        }
        return declaringClass.getEnclosedElements(
                io.micronaut.inject.ast.ElementQuery.ALL_METHODS).stream()
            .anyMatch(method -> method.hasDeclaredAnnotation(marker));
    }

    /**
     * The element the bean was declared by: the class itself, or the producer that produces it.
     */
    private Element declaration() {
        return producer == null ? declaringClass : producer;
    }

    /**
     * The type of the bean, which is the class itself or what the producer produces.
     *
     * @return The type
     */
    ClassElement beanType() {
        if (producer instanceof MethodElement method) {
            return method.getGenericReturnType();
        }
        if (producer instanceof FieldElement field) {
            return field.getGenericType();
        }
        return declaringClass;
    }

    @Override
    public ScopeInfo scope() {
        String scope = declaration().getAnnotationMetadata()
            .stringValue(CdiScope.class)
            .orElse(Cdi.DEPENDENT);
        boolean normal = declaration().getAnnotationMetadata()
            .booleanValue(CdiScope.class, "normal")
            .orElse(false);
        return new ElementScopeInfo(scope, normal);
    }

    @Override
    public Collection<Type> types() {
        List<Type> types = new ArrayList<>();
        collect(beanType(), types);
        return types;
    }

    private static void collect(@Nullable ClassElement type, List<Type> types) {
        if (type == null) {
            return;
        }
        types.add(ElementTypes.of(type));
        if (type.isArray() || type.isPrimitive()) {
            return;
        }
        type.getInterfaces().forEach(each -> collect(each, types));
        type.getSuperType().ifPresent(each -> collect(each, types));
    }

    @Override
    public Collection<AnnotationInfo> qualifiers() {
        List<AnnotationInfo> qualifiers = new ArrayList<>();
        for (String name : declaration().getAnnotationMetadata()
            .getAnnotationNamesByStereotype(Cdi.QUALIFIER)) {
            io.micronaut.core.annotation.AnnotationValue<?> annotation = declaration().getAnnotation(name);
            if (annotation != null) {
                qualifiers.add(new ElementAnnotationInfo(annotation));
            }
        }
        return qualifiers;
    }

    @Override
    public ClassInfo declaringClass() {
        return new ElementClassInfo(declaringClass);
    }

    @Override
    public boolean isClassBean() {
        return producer == null;
    }

    @Override
    public boolean isProducerMethod() {
        return producer instanceof MethodElement;
    }

    @Override
    public boolean isProducerField() {
        return producer instanceof FieldElement;
    }

    @Override
    public boolean isSynthetic() {
        // a bean described here is one the compiler saw; a synthetic bean has no class to have been compiled
        return false;
    }

    @Override
    public @Nullable MethodInfo producerMethod() {
        return producer instanceof MethodElement method
            ? new ElementMethodInfo(method, declaringClass()) : null;
    }

    @Override
    public @Nullable FieldInfo producerField() {
        return producer instanceof FieldElement field
            ? new ElementFieldInfo(field, declaringClass()) : null;
    }

    @Override
    public boolean isAlternative() {
        return declaration().hasAnnotation(Cdi.ALTERNATIVE);
    }

    @Override
    public @Nullable Integer priority() {
        return declaration().getAnnotationMetadata().intValue(Cdi.PRIORITY, "value")
            .stream().boxed().findFirst().orElse(null);
    }

    @Override
    public @Nullable String name() {
        return declaration().getAnnotationMetadata().stringValue("jakarta.inject.Named")
            // a stereotype-supplied default name is recorded as CdiName, with no Named materialized
            .or(() -> declaration().getAnnotationMetadata().stringValue("io.micronaut.cdi.annotation.CdiName"))
            .orElse(null);
    }

    @Override
    public @Nullable DisposerInfo disposer() {
        // the disposer of a produced bean is resolved while the producer is compiled, and is reported by the
        // method it resolved to rather than by a model of it
        return null;
    }

    @Override
    public Collection<StereotypeInfo> stereotypes() {
        // a stereotype carries a scope, qualifiers and a name, each of which is reported on the bean itself
        return List.of();
    }

    @Override
    public Collection<InjectionPointInfo> injectionPoints() {
        // the injection points of a bean are resolved by Micronaut as it injects them, and are not described
        // again here
        return List.of();
    }

    @Override
    public String toString() {
        return "Bean[" + beanType().getName() + "]";
    }
}
