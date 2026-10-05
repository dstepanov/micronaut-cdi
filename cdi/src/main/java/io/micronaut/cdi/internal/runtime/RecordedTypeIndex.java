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

import io.micronaut.cdi.internal.metadata.CdiTypeIndex;
import io.micronaut.context.BeanContext;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.type.Argument;
import io.micronaut.inject.BeanDefinition;
import jakarta.inject.Singleton;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The generic hierarchies of the classes the application was compiled with, as the processor recorded them:
 * what an event of a class is matched by, read from compiled metadata rather than from the class's generic
 * signature, and held as the arguments the container works with.
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
            // the classes that hold an index are found by the annotation on their references: only theirs are loaded
            for (BeanDefinition<?> definition : beanContext.getBeanDefinitions(
                io.micronaut.inject.qualifiers.Qualifiers.byStereotype(CdiTypeIndex.class))) {
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
        @Nullable List<Argument<?>> closureOf(Argument<?> type) {
            Map<String, Argument<?>> arguments = argumentsOf(type);
            List<Argument<?>> closure = new ArrayList<>(supertypes.size() + 1);
            closure.add(type);
            for (AnnotationValue<Annotation> supertype : supertypes) {
                Argument<?> resolved = RecordedTypes.find(supertype);
                if (resolved == null) {
                    return null;
                }
                closure.add(CdiTypes.substitute(resolved, arguments, true));
            }
            return closure;
        }

        /**
         * The class over its own type variables, which is the type a bean of a generic class has.
         *
         * @param type The class
         * @return The class, or its parameterization over its variables
         */
        Argument<?> declaredTypeOf(Class<?> type) {
            if (variables.isEmpty()) {
                return Argument.of(type);
            }
            Argument<?>[] own = new Argument<?>[variables.size()];
            for (int i = 0; i < own.length; i++) {
                own[i] = CdiTypes.variable(variables.get(i));
            }
            return Argument.of(type, (String) null, own);
        }

        private Map<String, Argument<?>> argumentsOf(Argument<?> type) {
            Map<String, Argument<?>> arguments = new HashMap<>();
            if (CdiTypes.isParameterized(type)) {
                Argument<?>[] actual = type.getTypeParameters();
                for (int i = 0; i < actual.length && i < variables.size(); i++) {
                    arguments.put(variables.get(i), actual[i]);
                }
            }
            return arguments;
        }
    }
}
