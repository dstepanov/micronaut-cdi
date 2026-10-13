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

import io.micronaut.cdi.internal.metadata.CdiName;
import io.micronaut.cdi.lang.model.ast.ElementAnnotationInfo;
import io.micronaut.cdi.lang.model.ast.ElementClassInfo;
import io.micronaut.cdi.lang.model.ast.ElementFieldInfo;
import io.micronaut.cdi.lang.model.ast.ElementMethodInfo;
import io.micronaut.cdi.lang.model.ast.ElementTypes;
import io.micronaut.cdi.internal.metadata.CdiScope;
import io.micronaut.cdi.processor.Cdi;
import io.micronaut.core.annotation.Internal;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.Element;
import io.micronaut.inject.ast.FieldElement;
import io.micronaut.inject.ast.MemberElement;
import io.micronaut.inject.ast.MethodElement;
import io.micronaut.inject.visitor.VisitorContext;
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

    /**
     * The qualifiers a bean is not qualified by as far as the specification goes: what Micronaut writes on a
     * bean of its own accord, and the markers {@code Any} and {@code Default} are read into, which are reported
     * as themselves.
     */
    private static final java.util.Set<String> NOT_QUALIFIERS_OF_THE_BEAN = java.util.Set.of(
        Cdi.ANY,
        io.micronaut.cdi.internal.metadata.CdiAny.class.getName(),
        "io.micronaut.context.annotation.Primary",
        "io.micronaut.context.annotation.Secondary",
        "io.micronaut.context.annotation.Any",
        "io.micronaut.context.annotation.Type"
    );

    private final ClassElement declaringClass;
    private final @Nullable MemberElement producer;
    private final VisitorContext context;

    ElementBeanInfo(ClassElement declaringClass, @Nullable MemberElement producer, VisitorContext context) {
        this.declaringClass = declaringClass;
        this.producer = producer;
        this.context = context;
    }

    @Override
    public boolean isInterceptor() {
        return producer == null && declaringClass.hasDeclaredAnnotation("jakarta.interceptor.Interceptor");
    }

    @Override
    public java.util.Collection<jakarta.enterprise.lang.model.AnnotationInfo> interceptorBindings() {
        java.util.List<jakarta.enterprise.lang.model.AnnotationInfo> bindings = new java.util.ArrayList<>();
        io.micronaut.core.annotation.AnnotationMetadata metadata = declaringClass.getAnnotationMetadata();
        for (String name : metadata.getAnnotationNamesByStereotype("jakarta.interceptor.InterceptorBinding")) {
            // every occurrence of a repeatable binding, as the container reports them
            List<io.micronaut.core.annotation.AnnotationValue<java.lang.annotation.Annotation>> occurrences =
                metadata.getAnnotationValuesByName(name);
            if (!occurrences.isEmpty()) {
                for (io.micronaut.core.annotation.AnnotationValue<?> occurrence : occurrences) {
                    bindings.add(new ElementAnnotationInfo(occurrence, context));
                }
                continue;
            }
            // a binding only a stereotype carries
            io.micronaut.core.annotation.AnnotationValue<?> value = metadata.getAnnotation(name);
            if (value != null) {
                bindings.add(new ElementAnnotationInfo(value, context));
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
     * What the bean was declared with: the annotations of the class, or of the producer alone. The metadata of a
     * method carries its class's as well, and the class's scope, name or {@code Typed} are not the producer's.
     */
    private io.micronaut.core.annotation.AnnotationMetadata declared() {
        if (producer instanceof MethodElement method) {
            return method.getMethodAnnotationMetadata();
        }
        return declaration().getAnnotationMetadata();
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
        String scope = declared().stringValue(CdiScope.class).orElse(Cdi.DEPENDENT);
        boolean normal = declared().booleanValue(CdiScope.class, "normal").orElse(false);
        return new ElementScopeInfo(scope, normal);
    }

    /**
     * The bean types as the container resolves the bean by them (section 2.2): the type and everything above
     * it, each once, narrowed to what the bean named with {@code Typed}, without a parameterized type that holds
     * a wildcard, and with {@code Object}, which every bean has, once and last.
     */
    @Override
    public Collection<Type> types() {
        java.util.Set<Type> closure = new java.util.LinkedHashSet<>();
        // the closure the container records, the supertypes of a raw type erased
        io.micronaut.cdi.processor.visitor.BeanTypesVisitor.walkClosure(beanType(), (type, bindings, erased) ->
            closure.add(erased ? ElementTypes.rawClassOf(type, List.of(), context) : ElementTypes.of(type, context)));
        java.util.Set<String> narrowed = new java.util.HashSet<>(java.util.Arrays.asList(
            declared().stringValues("jakarta.enterprise.inject.Typed")));
        boolean typed = declared().hasAnnotation("jakarta.enterprise.inject.Typed");
        List<Type> types = new ArrayList<>();
        for (Type type : closure) {
            if (typed && !narrowed.contains(nameOf(type))) {
                continue;
            }
            if (!holdsAWildcard(type)) {
                types.add(type);
            }
        }
        context.getClassElement(Object.class).ifPresent(object -> types.add(ElementTypes.of(object, context)));
        return types;
    }

    /**
     * Whether a type of the given class is among the bean types, as a registration method asks for the beans of a
     * type: what {@code Typed} leaves out is not.
     *
     * @param className The binary name of the class
     * @return Whether the bean has a type of the class
     */
    boolean hasTypeOfClass(String className) {
        for (Type type : types()) {
            if (className.equals(nameOf(type))) {
                return true;
            }
        }
        return false;
    }

    private static String nameOf(Type type) {
        if (type.isClass()) {
            return type.asClass().declaration().name();
        }
        if (type.isParameterizedType()) {
            return type.asParameterizedType().declaration().name();
        }
        return type.toString();
    }

    private static boolean holdsAWildcard(Type type) {
        return type.isParameterizedType() && type.asParameterizedType().typeArguments().stream()
            .anyMatch(argument -> argument.isWildcardType() || holdsAWildcard(argument));
    }

    /**
     * The qualifiers as the container resolves the bean by them: {@code Any}, which every bean has, and the ones
     * the bean was compiled with - each occurrence of a repeatable one - without the qualifiers Micronaut writes of
     * its own accord, nor a {@code Named} a stereotype gave rather than the bean. A producer has the ones it
     * declares, not its class's.
     */
    @Override
    public Collection<AnnotationInfo> qualifiers() {
        List<AnnotationInfo> qualifiers = new ArrayList<>();
        qualifiers.add(new ElementAnnotationInfo(
            io.micronaut.core.annotation.AnnotationValue.builder(Cdi.ANY).build(), context));
        io.micronaut.core.annotation.AnnotationMetadata metadata = producer == null
            ? declaringClass.getAnnotationMetadata() : producer.getAnnotationMetadata().getDeclaredMetadata();
        for (String name : metadata.getAnnotationNamesByStereotype(Cdi.QUALIFIER)) {
            if (NOT_QUALIFIERS_OF_THE_BEAN.contains(name)
                || ("jakarta.inject.Named".equals(name) && metadata.hasAnnotation(CdiName.class))) {
                continue;
            }
            List<io.micronaut.core.annotation.AnnotationValue<java.lang.annotation.Annotation>> occurrences =
                metadata.getAnnotationValuesByName(name);
            for (io.micronaut.core.annotation.AnnotationValue<?> annotation : occurrences) {
                qualifiers.add(new ElementAnnotationInfo(annotation, context));
            }
        }
        return qualifiers;
    }

    @Override
    public ClassInfo declaringClass() {
        return new ElementClassInfo(declaringClass, context);
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
            ? new ElementMethodInfo(method, declaringClass(), context) : null;
    }

    @Override
    public @Nullable FieldInfo producerField() {
        return producer instanceof FieldElement field
            ? new ElementFieldInfo(field, declaringClass(), context) : null;
    }

    /**
     * Whether the bean is an alternative, written so or through a stereotype, as the container reads it: a
     * producer is one where it is declared one or its class is.
     */
    @Override
    public boolean isAlternative() {
        boolean declaredOne = declared().hasAnnotation(Cdi.ALTERNATIVE) || declared().hasStereotype(Cdi.ALTERNATIVE);
        return producer == null ? declaredOne
            : declaredOne || declaringClass.getAnnotationMetadata().hasStereotype(Cdi.ALTERNATIVE);
    }

    /**
     * The priority of the bean: the one it declares, or for a producer the one of the class that declares it,
     * which is what selects an alternative producer (section 2.7.2).
     */
    @Override
    public @Nullable Integer priority() {
        java.util.OptionalInt declared = declared().intValue(Cdi.PRIORITY, "value");
        if (declared.isEmpty() && producer != null) {
            declared = declaringClass.getAnnotationMetadata().intValue(Cdi.PRIORITY, "value");
        }
        return declared.isPresent() ? declared.getAsInt() : null;
    }

    @Override
    public @Nullable String name() {
        return declared().stringValue("jakarta.inject.Named")
            // a stereotype-supplied default name is recorded as CdiName, with no Named materialized
            .or(() -> declared().stringValue(CdiName.class))
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
