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

import io.micronaut.cdi.spi.CdiReflection;
import io.micronaut.cdi.spi.InjectionSite;
import io.micronaut.cdi.internal.runtime.CdiTypes;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.reflect.GenericTypeUtils;
import io.micronaut.core.reflect.ReflectionUtils;
import io.micronaut.core.type.Argument;
import io.micronaut.reflection.ReflectionArguments;
import io.micronaut.reflection.ReflectionAnnotations;
import io.micronaut.reflection.ReflectionExecutables;
import jakarta.enterprise.inject.spi.Annotated;
import jakarta.inject.Singleton;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
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

    /**
     * The primitive types, by the names a recorded parameter type names them by.
     */
    private static final Map<String, Class<?>> PRIMITIVES = Map.of(
        "boolean", boolean.class, "byte", byte.class, "char", char.class, "short", short.class, "int", int.class,
        "long", long.class, "float", float.class, "double", double.class);

    @SuppressWarnings("unchecked")
    @Override
    public <A extends Annotation> A annotation(Class<A> type, @Nullable AnnotationValue<?> value) {
        // Micronaut's synthesiser serves the members the value leaves out from the defaults of the type, and
        // compares and hashes the instance as java.lang.annotation.Annotation specifies
        AnnotationValue<A> values = value == null ? AnnotationValue.builder(type).build() : (AnnotationValue<A>) value;
        return ReflectionAnnotations.synthesize(type, values);
    }

    @Override
    public List<Type> typeClosureOf(Type type, boolean arrayStops) {
        return closureOf(type, arrayStops);
    }

    private static List<Type> closureOf(Type type, boolean arrayStops) {
        List<Type> closure = new ArrayList<>();
        Class<?> raw = CdiTypes.rawClassOf(type);
        if (raw == null || raw == Object.class) {
            // a type variable or a wildcard names no type of its own
            return closure;
        }
        closure.add(type);
        if (arrayStops && (raw.isArray() || raw.isPrimitive())) {
            return closure;
        }
        // Micronaut's reading of the generic signatures above the type, which keeps a wildcard a wildcard and a
        // variable a variable, with what a subtype says about its parameters carried into its supertypes; of an
        // array, the arrays of the supertypes of its component, the arrays of fewer dimensions it is an array of,
        // and what every array implements. The container hands over the types it rendered itself, whose variables
        // carry their bounds but no annotated bounds, which Micronaut reads the variable through
        List<Argument<?>> supertypes = ReflectionArguments.typeClosureOf(ReflectionArguments.ofExact(type));
        for (Argument<?> supertype : supertypes.subList(1, supertypes.size())) {
            if (supertype.getType() != Object.class) {
                closure.add(ReflectionArguments.toExactType(supertype));
            }
        }
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
        Set<Type> closure = new LinkedHashSet<>(closureOf(type, true));
        closure.add(Object.class);
        return closure;
    }

    @Override
    public Type declaredTypeOf(Class<?> type) {
        TypeVariable<?>[] variables = type.getTypeParameters();
        return variables.length == 0 ? type : GenericTypeUtils.parameterizedType(type, variables);
    }

    @Override
    public Type eventTypeOf(Class<?> runtimeClass, Type declaredType) {
        TypeVariable<?>[] variables = runtimeClass.getTypeParameters();
        if (variables.length == 0) {
            return runtimeClass;
        }
        // the variables the class declares are resolved against the type the event was fired as: the class's
        // own view of that type is unified with it. The variables are told apart by their names, which are the
        // class's own: the closure renders a variable of its own rather than the one the class declares
        Map<String, Type> resolution = new HashMap<>();
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
            Type resolved = resolution.get(variables[i].getName());
            if (resolved instanceof WildcardType wildcard) {
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
        return GenericTypeUtils.parameterizedType(runtimeClass, arguments);
    }

    private static void unify(Type own, Type declared, Map<String, Type> resolution) {
        if (own instanceof TypeVariable<?> variable) {
            resolution.put(variable.getName(), declared);
            return;
        }
        if (own instanceof GenericArrayType ownArray) {
            // an array of a variable, or of a type that uses one, T[] or List<T>[], is unified through its component
            if (declared instanceof GenericArrayType declaredArray) {
                unify(ownArray.getGenericComponentType(), declaredArray.getGenericComponentType(), resolution);
            } else if (declared instanceof Class<?> declaredClass && declaredClass.isArray()) {
                unify(ownArray.getGenericComponentType(), declaredClass.getComponentType(), resolution);
            }
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
        Retention retention = annotationType.getAnnotation(Retention.class);
        return retention != null && retention.value() == RetentionPolicy.RUNTIME;
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
        if (!site.declaredParameterTypes().isEmpty() || site.parameterTypes().isEmpty()) {
            // the processor recorded the declaration itself: the class that declares the method, and the erasure of
            // the parameter types it declares
            Class<?>[] declared = declaredTypesOf(site);
            if (declared != null) {
                Method exact = ReflectionUtils.findMethod(site.declaringClass(), site.memberName(),
                    declared).orElse(null);
                if (exact != null) {
                    return exact;
                }
            }
        }
        // a bean's definition compiles the parameters of a method its class inherits with the type arguments the
        // class gives the type variables of the class above it, so T[] of Bar<T> is compiled as String[] for
        // Foo extends Bar<String>, which the erased Object[] the method declares is assignable from. Micronaut finds
        // the declaration the compiled types resolve to, through the superclasses and the interfaces; where another
        // declaration fits them as well, nothing tells which of them was injected, and none is answered
        Method found = ReflectionExecutables.findMethod(site.declaringClass(), site.memberName(),
            parameterTypes).orElse(null);
        if (found == null || Arrays.equals(found.getParameterTypes(), parameterTypes)) {
            // a declaration of the compiled types themselves is the one: no other of its name declares them
            return found;
        }
        return isTheOnlyFit(site.declaringClass(), found, parameterTypes) ? found : null;
    }

    /**
     * The classes of the parameter types the site records the method to declare, or {@code null} where one of them
     * cannot be loaded.
     */
    private static Class<?> @Nullable [] declaredTypesOf(InjectionSite site) {
        ClassLoader loader = site.declaringClass().getClassLoader();
        Class<?>[] types = new Class<?>[site.declaredParameterTypes().size()];
        for (int i = 0; i < types.length; i++) {
            String name = site.declaredParameterTypes().get(i);
            int dimensions = 0;
            while (name.endsWith("[]")) {
                name = name.substring(0, name.length() - 2);
                dimensions++;
            }
            Class<?> type = PRIMITIVES.get(name);
            if (type == null) {
                try {
                    type = Class.forName(name, false, loader);
                } catch (ClassNotFoundException | LinkageError e) {
                    return null;
                }
            }
            for (int d = 0; d < dimensions; d++) {
                type = type.arrayType();
            }
            types[i] = type;
        }
        return types;
    }

    /**
     * Whether the method is the only declaration of its name in the class and above it that the compiled types
     * fit: a method it overrides is the same declaration, any other is another.
     */
    private static boolean isTheOnlyFit(Class<?> type, Method found, Class<?>[] compiledTypes) {
        Set<List<Class<?>>> fitting = new HashSet<>();
        Deque<Class<?>> classes = new ArrayDeque<>();
        Set<Class<?>> seen = new HashSet<>();
        classes.add(type);
        while (!classes.isEmpty()) {
            Class<?> current = classes.poll();
            if (!seen.add(current)) {
                continue;
            }
            for (Method candidate : current.getDeclaredMethods()) {
                if (!candidate.isSynthetic() && candidate.getName().equals(found.getName())
                    && fits(candidate.getParameterTypes(), compiledTypes)) {
                    fitting.add(List.of(candidate.getParameterTypes()));
                }
            }
            if (current.getSuperclass() != null) {
                classes.add(current.getSuperclass());
            }
            classes.addAll(List.of(current.getInterfaces()));
        }
        return fitting.size() == 1;
    }

    private static boolean fits(Class<?>[] declared, Class<?>[] compiledTypes) {
        if (declared.length != compiledTypes.length) {
            return false;
        }
        for (int i = 0; i < declared.length; i++) {
            if (!declared[i].isAssignableFrom(compiledTypes[i])) {
                return false;
            }
        }
        return true;
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
