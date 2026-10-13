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
package io.micronaut.cdi.reflection;

import io.micronaut.cdi.internal.runtime.CdiParameterizedType;
import io.micronaut.cdi.spi.CdiReflection;
import io.micronaut.cdi.spi.InjectionSite;
import io.micronaut.cdi.internal.runtime.CdiTypes;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.reflect.ReflectionUtils;
import io.micronaut.reflection.ReflectionAnnotations;
import io.micronaut.reflection.ReflectionExecutables;
import jakarta.enterprise.inject.spi.Annotated;
import jakarta.inject.Singleton;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Answers the parts of the specification's API that hand out reflection objects, by reflection.
 *
 * <p>An annotation materialized here is synthesised by Micronaut's {@link ReflectionAnnotations}, which
 * implements {@link Object#equals} and {@link Object#hashCode} as {@code java.lang.annotation.Annotation}
 * specifies them: that is what makes it comparable with the annotation literals the specification's own API is
 * full of - a program that asks whether a bean's qualifiers contain {@code new HairyQualifier(false)} is comparing
 * a literal of its own with one of these. The values of an annotation instance are read by the same class.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Singleton
@Internal
public final class ReflectiveCdi implements CdiReflection {

    @SuppressWarnings("unchecked")
    @Override
    public <A extends Annotation> A annotation(Class<A> type, @Nullable AnnotationValue<?> value) {
        // Micronaut's synthesiser serves the members the value leaves out from the defaults of the type, and
        // compares and hashes the instance as java.lang.annotation.Annotation specifies
        AnnotationValue<A> values = value == null ? AnnotationValue.builder(type).build() : (AnnotationValue<A>) value;
        return ReflectionAnnotations.synthesize(type, values);
    }

    @Override
    public java.util.List<Type> typeClosureOf(Type type, boolean arrayStops) {
        java.util.List<Type> closure = new java.util.ArrayList<>();
        collectClosure(type, closure, arrayStops);
        return closure;
    }

    /**
     * The type closure of an annotated element of the given type: the type, every type above it, and
     * {@code java.lang.Object}, each once.
     *
     * @param type The type
     * @return The closure
     */
    static Set<Type> annotatedTypeClosure(Type type) {
        java.util.List<Type> collected = new java.util.ArrayList<>();
        collectClosure(type, collected, true);
        Set<Type> closure = new LinkedHashSet<>(collected);
        closure.add(Object.class);
        return closure;
    }

    private static void collectClosure(@Nullable Type type, java.util.List<Type> closure, boolean arrayStops) {
        if (type == null || type == Object.class) {
            return;
        }
        Class<?> raw = CdiTypes.rawClassOf(type);
        if (raw == null) {
            // a type variable or a wildcard names no type of its own
            return;
        }
        closure.add(type);
        if (arrayStops && (raw.isArray() || raw.isPrimitive())) {
            // a primitive has no supertypes to collect in the first place
            return;
        }
        Map<TypeVariable<?>, Type> substitution = new java.util.HashMap<>();
        if (type instanceof ParameterizedType parameterized) {
            TypeVariable<?>[] variables = raw.getTypeParameters();
            Type[] arguments = parameterized.getActualTypeArguments();
            for (int i = 0; i < variables.length && i < arguments.length; i++) {
                substitution.put(variables[i], arguments[i]);
            }
        }
        for (Type anInterface : raw.getGenericInterfaces()) {
            collectClosure(substitute(anInterface, substitution), closure, arrayStops);
        }
        collectClosure(substitute(raw.getGenericSuperclass(), substitution), closure, arrayStops);
    }

    /**
     * The type with the given variables substituted, so that what a subtype says about its parameters carries
     * into the supertypes it collects.
     */
    private static @Nullable Type substitute(@Nullable Type type, Map<TypeVariable<?>, Type> substitution) {
        if (substitution.isEmpty() || type == null) {
            return type;
        }
        if (type instanceof TypeVariable<?> variable) {
            return substitution.getOrDefault(variable, variable);
        }
        if (type instanceof ParameterizedType parameterized && parameterized.getRawType() instanceof Class<?> rawType) {
            Type[] arguments = parameterized.getActualTypeArguments();
            Type[] substituted = new Type[arguments.length];
            boolean changed = false;
            for (int i = 0; i < arguments.length; i++) {
                substituted[i] = substitute(arguments[i], substitution);
                changed |= substituted[i] != arguments[i];
            }
            return changed ? CdiParameterizedType.of(rawType, substituted) : type;
        }
        return type;
    }

    @Override
    public Type declaredTypeOf(Class<?> type) {
        TypeVariable<?>[] variables = type.getTypeParameters();
        return variables.length == 0 ? type : CdiParameterizedType.of(type, variables);
    }

    @Override
    public Type eventTypeOf(Class<?> runtimeClass, Type declaredType) {
        TypeVariable<?>[] variables = runtimeClass.getTypeParameters();
        if (variables.length == 0) {
            return runtimeClass;
        }
        // the variables the class declares are resolved against the type the event was fired as: the class's
        // own view of that type is unified with it
        Map<TypeVariable<?>, Type> resolution = new java.util.HashMap<>();
        Class<?> declaredRaw = CdiTypes.rawClassOf(declaredType);
        if (declaredType instanceof ParameterizedType declaredParameterized && declaredRaw != null) {
            for (Type supertype : typeClosureOf(declaredTypeOf(runtimeClass), false)) {
                if (CdiTypes.rawClassOf(supertype) == declaredRaw && supertype instanceof ParameterizedType own) {
                    Type[] ownArguments = own.getActualTypeArguments();
                    Type[] declaredArguments = declaredParameterized.getActualTypeArguments();
                    for (int i = 0; i < ownArguments.length && i < declaredArguments.length; i++) {
                        unify(ownArguments[i], declaredArguments[i], resolution);
                    }
                    break;
                }
            }
        }
        Type[] arguments = new Type[variables.length];
        for (int i = 0; i < variables.length; i++) {
            Type resolved = resolution.get(variables[i]);
            if (resolved instanceof java.lang.reflect.WildcardType wildcard) {
                Type[] uppers = wildcard.getUpperBounds();
                resolved = uppers.length > 0 ? uppers[0] : Object.class;
            }
            if (resolved == null || resolved instanceof TypeVariable<?>) {
                throw new IllegalArgumentException("The type variable " + variables[i].getName() + " of "
                    + runtimeClass.getName() + " is not resolved by the type the event was fired as: "
                    + declaredType.getTypeName());
            }
            arguments[i] = resolved;
        }
        return CdiParameterizedType.of(runtimeClass, arguments);
    }

    private static void unify(Type own, Type declared, Map<TypeVariable<?>, Type> resolution) {
        if (own instanceof TypeVariable<?> variable) {
            resolution.put(variable, declared);
            return;
        }
        if (own instanceof ParameterizedType ownParameterized
            && declared instanceof ParameterizedType declaredParameterized) {
            Type[] ownArguments = ownParameterized.getActualTypeArguments();
            Type[] declaredArguments = declaredParameterized.getActualTypeArguments();
            for (int i = 0; i < ownArguments.length && i < declaredArguments.length; i++) {
                unify(ownArguments[i], declaredArguments[i], resolution);
            }
        }
    }

    @Override
    public AnnotationValue<?> valuesOf(Annotation annotation) {
        // the members the specification marks Nonbinding are listed under $nonBinding by the customizer this module
        // registers, which every reflective read of Micronaut's applies
        return ReflectionAnnotations.valueOf(annotation);
    }

    @Override
    public boolean isRetainedAtRuntime(Class<? extends Annotation> annotationType) {
        java.lang.annotation.Retention retention = annotationType.getAnnotation(java.lang.annotation.Retention.class);
        return retention != null && retention.value() == java.lang.annotation.RetentionPolicy.RUNTIME;
    }

    @Override
    public @Nullable Member member(InjectionSite site) {
        Class<?>[] parameterTypes = site.parameterTypes().toArray(new Class<?>[0]);
        if (site.field()) {
            return ReflectionUtils.findField(site.declaringClass(), site.memberName()).orElse(null);
        }
        if (site.isConstructor()) {
            Constructor<?> exact = ReflectionUtils.findConstructor(site.declaringClass(), parameterTypes).orElse(null);
            return exact != null ? exact
                : declaredFor(site.declaringClass().getDeclaredConstructors(), parameterTypes);
        }
        // a bean's definition compiles the parameters of a method its class inherits with the type arguments the
        // class gives the type variables of the class above it, so T[] of Bar<T> is compiled as String[] for
        // Foo extends Bar<String>, which the erased Object[] the method declares is assignable from. Micronaut finds
        // the declaration the compiled types resolve to, through the superclasses and the interfaces, the most
        // specific one where an overload fits them as well
        return ReflectionExecutables.findMethod(site.declaringClass(), site.memberName(), parameterTypes)
            .orElse(null);
    }

    /**
     * The one constructor among the given ones whose parameters the compiled types fit, where none declares them
     * exactly: a constructor is not inherited, so the class declares it, and two that the compiled types fit are
     * told apart by nothing.
     */
    private static @Nullable Executable declaredFor(Executable[] candidates, Class<?>[] compiledTypes) {
        Executable found = null;
        for (Executable candidate : candidates) {
            if (candidate.isSynthetic() || candidate.getParameterCount() != compiledTypes.length) {
                continue;
            }
            Class<?>[] declared = candidate.getParameterTypes();
            boolean fits = true;
            for (int i = 0; i < declared.length && fits; i++) {
                fits = declared[i].isAssignableFrom(compiledTypes[i]);
            }
            if (fits) {
                if (found != null) {
                    // two overloads the compiled types fit, which nothing tells apart
                    return null;
                }
                found = candidate;
            }
        }
        return found;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Override
    public Annotated annotated(InjectionSite site) {
        Member member = member(site);
        if (member instanceof Field field) {
            return new ReflectiveAnnotatedField(field);
        }
        if (member instanceof Executable executable && site.index() >= 0
            && site.index() < executable.getParameterCount()) {
            return new ReflectiveAnnotatedParameter(executable, site.index());
        }
        throw new UnsupportedOperationException("The annotated model of the injection point at " + site
            + " cannot be read back from the compiled class");
    }

    @Override
    public boolean isAnnotated(Class<? extends Annotation> annotationType,
                               Class<? extends Annotation> metaAnnotation) {
        // the specification asks what is written on the annotation type itself. Micronaut's reflective metadata
        // answers a different question: it adds the stereotypes below each meta-annotation, folds a repeatable
        // one into its container and leaves out java.lang.annotation, whose Repeatable is asked here
        return annotationType.isAnnotationPresent(metaAnnotation);
    }

    @Override
    public Set<Annotation> metaAnnotationsOf(Class<? extends Annotation> annotationType) {
        // the instances the class holds, as the definition of a stereotype or a binding reports them, rather
        // than instances synthesised back from Micronaut's metadata of them
        Set<Annotation> annotations = new LinkedHashSet<>();
        for (Annotation annotation : annotationType.getAnnotations()) {
            if (!annotation.annotationType().getName().startsWith("java.lang.annotation.")) {
                annotations.add(annotation);
            }
        }
        return annotations;
    }
}
