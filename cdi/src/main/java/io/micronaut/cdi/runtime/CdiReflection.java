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

import io.micronaut.context.BeanContext;
import io.micronaut.core.annotation.AnnotationValue;
import jakarta.enterprise.inject.spi.Annotated;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.lang.reflect.Member;
import java.util.Set;

/**
 * What the specification's API asks for that only reflection can answer.
 *
 * <p>A bean is resolved and injected from what was compiled for it, and this module reads no class back. Parts
 * of the specification's API, though, are written in terms of reflection objects and hand them to the program
 * that asks: the {@code Member} of an injection point, its {@code Annotated} model, an annotation instance, the
 * annotations of an annotation class the build never saw. Each of those is one method here, and the
 * implementation is in a module of its own, {@value #MODULE}, which an application adds when it calls that part
 * of the API.</p>
 *
 * <p>The implementation is a bean, looked up when one of those calls is made. Where the module is absent the
 * call fails with an {@link UnsupportedOperationException} that names the module, rather than answering with
 * something that would read as there being nothing to report.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
public interface CdiReflection {

    /**
     * The coordinates of the module that implements this.
     */
    String MODULE = "io.micronaut.cdi:micronaut-cdi-reflection";

    /**
     * An instance of an annotation, from the values the annotation was written with.
     *
     * @param type  The annotation type
     * @param value The values it was written with, or {@code null} for the defaults the type declares
     * @param <A>   The annotation type
     * @return The annotation
     */
    <A extends Annotation> A annotation(Class<A> type, @Nullable AnnotationValue<?> value);

    /**
     * The values an annotation instance holds, every member among them, in the form compiled metadata records
     * an annotation in.
     *
     * @param annotation The annotation instance
     * @return Its values
     */
    AnnotationValue<?> valuesOf(Annotation annotation);

    /**
     * The members of an annotation type that are excluded from the comparison of two annotations of the type,
     * which the specification says with {@code jakarta.enterprise.util.Nonbinding}.
     *
     * @param annotationType The annotation type
     * @return The names of the members
     */
    Set<String> nonbindingMembersOf(Class<? extends Annotation> annotationType);

    /**
     * Whether an annotation type is retained at runtime.
     *
     * @param annotationType The annotation type
     * @return Whether it is
     */
    boolean isRetainedAtRuntime(Class<? extends Annotation> annotationType);

    /**
     * The type closure of a type, read from the generic signatures of its class and of the classes above it:
     * the type, and every class and interface above it with the type arguments the hierarchy gives each,
     * {@code Object} left out.
     *
     * @param type       The type
     * @param arrayStops Whether an array and a primitive have no closure beyond themselves, as a bean type has
     * @return The closure, the type first
     */
    java.util.List<java.lang.reflect.Type> typeClosureOf(java.lang.reflect.Type type, boolean arrayStops);

    /**
     * A class as the type a bean of it has: itself where it declares no type variable, and its
     * parameterization over its own variables where it does.
     *
     * @param type The class
     * @return The type
     */
    java.lang.reflect.Type declaredTypeOf(Class<?> type);

    /**
     * The type of an event of the given runtime class that was fired as the given type (section 2.8.1): the
     * class with its type variables resolved from the type it was fired as.
     *
     * @param runtimeClass The class of the event object
     * @param declaredType The type the event was fired as
     * @return The event type
     * @throws IllegalArgumentException Where the type the event was fired as leaves a variable of the class
     *                                  unresolved
     */
    java.lang.reflect.Type eventTypeOf(Class<?> runtimeClass, java.lang.reflect.Type declaredType);

    /**
     * The member an injection point injects into.
     *
     * @param declaringClass The class that declares the injection point
     * @param memberName     The name of the member, {@code <init>} for a constructor
     * @param field          Whether the member is a field
     * @param injectedType   The raw type the point injects, which tells overloads apart
     * @return The member, or {@code null} where the class declares none of the name
     */
    @Nullable Member member(Class<?> declaringClass, String memberName, boolean field, Class<?> injectedType);

    /**
     * The annotated model of an injection point: the field, or the parameter of the constructor or method.
     *
     * @param member       The member the point injects into
     * @param injectedType The raw type the point injects
     * @param injectedName The name of the injected parameter
     * @return The annotated field or parameter
     */
    Annotated annotated(Member member, Class<?> injectedType, String injectedName);

    /**
     * Whether an annotation type is itself annotated with another.
     *
     * @param annotationType The annotation type asked about
     * @param metaAnnotation The annotation looked for on it
     * @return Whether the annotation type carries it
     */
    boolean isAnnotated(Class<? extends Annotation> annotationType, Class<? extends Annotation> metaAnnotation);

    /**
     * The annotations an annotation type is annotated with, less the ones of {@code java.lang.annotation}:
     * what the specification calls the definition of a stereotype or of an interceptor binding.
     *
     * @param annotationType The annotation type
     * @return The annotations written on it
     */
    Set<Annotation> metaAnnotationsOf(Class<? extends Annotation> annotationType);

    /**
     * The implementation the application has, for a call that needs it.
     *
     * @param beanContext The bean context of the container, or {@code null} where none is running
     * @param what        What needs it, to open the message with when the module is absent
     * @return The implementation
     * @throws UnsupportedOperationException Where the module is not on the classpath
     */
    static CdiReflection require(@Nullable BeanContext beanContext, String what) {
        CdiReflection reflection = beanContext == null ? null : beanContext.findBean(CdiReflection.class).orElse(null);
        if (reflection == null) {
            throw new UnsupportedOperationException(what + " is answered by reflection, which this module does not "
                + "use: add " + MODULE + " to the classpath of the application to have it");
        }
        return reflection;
    }

    /**
     * The implementation of the running container, for a call made where no bean context is at hand.
     *
     * @param what What needs it, to open the message with when the module is absent
     * @return The implementation
     * @throws UnsupportedOperationException Where the module is not on the classpath
     */
    static CdiReflection current(String what) {
        return require(CdiRunning.currentContext(), what);
    }
}
