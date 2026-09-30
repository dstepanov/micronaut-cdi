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

import io.micronaut.cdi.annotation.CdiTypeIndex;
import io.micronaut.context.BeanContext;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import io.micronaut.inject.BeanDefinition;
import jakarta.inject.Singleton;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The generic hierarchies of the classes the application was compiled with, as the processor recorded them:
 * what an event of a class is matched by, read from compiled metadata rather than from the class's generic
 * signature.
 *
 * <p>A class the application was not compiled with has no record. The module that reads classes answers for
 * it where it is there, and otherwise a class is matched as its raw class and the raw classes above it.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Singleton
@Internal
public final class RecordedTypeIndex {

    private final BeanContext beanContext;
    private volatile @Nullable Map<String, Entry> entries;

    public RecordedTypeIndex(BeanContext beanContext) {
        this.beanContext = beanContext;
    }

    /**
     * The index of the container that is current.
     *
     * @return The index, or {@code null} where no container is running
     */
    static @Nullable RecordedTypeIndex current() {
        BeanContext context = CdiRunning.currentContext();
        return context == null ? null : context.findBean(RecordedTypeIndex.class).orElse(null);
    }

    /**
     * What was recorded of the class of the given name.
     *
     * @param className The class name
     * @return The record, or {@code null} where the application was not compiled with the class
     */
    @Nullable Entry of(String className) {
        Map<String, Entry> resolved = entries;
        if (resolved == null) {
            resolved = new HashMap<>();
            for (BeanDefinition<?> definition : beanContext.getAllBeanDefinitions()) {
                AnnotationValue<CdiTypeIndex> index = definition.getAnnotationMetadata().getAnnotation(CdiTypeIndex.class);
                if (index == null) {
                    continue;
                }
                for (AnnotationValue<Annotation> entry : index.getAnnotations("value")) {
                    String name = entry.stringValue("name").orElse(null);
                    if (name != null) {
                        resolved.putIfAbsent(name, new Entry(List.of(entry.stringValues("variables")),
                            entry.getAnnotations("supertypes")));
                    }
                }
            }
            entries = resolved;
        }
        return resolved.get(className);
    }

    /**
     * The type with each type variable named among the given arguments replaced by the argument.
     */
    static Type substitute(Type type, Map<String, Type> arguments) {
        if (arguments.isEmpty()) {
            return type;
        }
        if (type instanceof TypeVariable<?> variable) {
            Type argument = arguments.get(variable.getName());
            return argument != null ? argument : type;
        }
        if (type instanceof ParameterizedType parameterized && parameterized.getRawType() instanceof Class<?> raw) {
            Type[] actual = parameterized.getActualTypeArguments();
            Type[] substituted = new Type[actual.length];
            boolean changed = false;
            for (int i = 0; i < actual.length; i++) {
                substituted[i] = substitute(actual[i], arguments);
                changed |= substituted[i] != actual[i];
            }
            return changed ? CdiParameterizedType.of(raw, substituted) : type;
        }
        if (type instanceof WildcardType wildcard) {
            Type[] upper = wildcard.getUpperBounds();
            Type[] lower = wildcard.getLowerBounds();
            Type[] substitutedUpper = new Type[upper.length];
            Type[] substitutedLower = new Type[lower.length];
            boolean changed = false;
            for (int i = 0; i < upper.length; i++) {
                substitutedUpper[i] = substitute(upper[i], arguments);
                changed |= substitutedUpper[i] != upper[i];
            }
            for (int i = 0; i < lower.length; i++) {
                substitutedLower[i] = substitute(lower[i], arguments);
                changed |= substitutedLower[i] != lower[i];
            }
            return changed ? new CdiWildcardType(substitutedUpper, substitutedLower) : type;
        }
        return type;
    }

    /**
     * What was recorded of one class.
     *
     * @param variables  The names of the type variables the class declares
     * @param supertypes The classes and interfaces above it, as the hierarchy parameterizes them
     */
    record Entry(List<String> variables, List<AnnotationValue<Annotation>> supertypes) {

        /**
         * The type closure of the class as the given type names it: the type, and every recorded supertype
         * with the variables of the class replaced by the type arguments given.
         *
         * @param type The class, or a parameterization of it
         * @return The closure, or {@code null} where the record refers to a class that is not there
         */
        @Nullable List<Type> closureOf(Type type) {
            Map<String, Type> arguments = argumentsOf(type);
            List<Type> closure = new ArrayList<>(supertypes.size() + 1);
            closure.add(type);
            for (AnnotationValue<Annotation> supertype : supertypes) {
                Type resolved = RecordedTypes.find(supertype);
                if (resolved == null) {
                    return null;
                }
                closure.add(substitute(resolved, arguments));
            }
            return closure;
        }

        /**
         * The class over its own type variables, which is the type a bean of a generic class has.
         *
         * @param type The class
         * @return The class, or its parameterization over its variables
         */
        Type declaredTypeOf(Class<?> type) {
            if (variables.isEmpty()) {
                return type;
            }
            Type[] own = new Type[variables.size()];
            for (int i = 0; i < own.length; i++) {
                own[i] = new CdiTypeVariable(variables.get(i), new Type[] {Object.class});
            }
            return CdiParameterizedType.of(type, own);
        }

        private Map<String, Type> argumentsOf(Type type) {
            Map<String, Type> arguments = new HashMap<>();
            if (type instanceof ParameterizedType parameterized) {
                Type[] actual = parameterized.getActualTypeArguments();
                for (int i = 0; i < actual.length && i < variables.size(); i++) {
                    arguments.put(variables.get(i), actual[i]);
                }
            }
            return arguments;
        }
    }
}
