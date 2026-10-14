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
    /**
     * What the definitions of the beans of the specification recorded of their classes, by class name.
     */
    private volatile @Nullable Map<String, Entry> beans;

    public RecordedTypeIndex(BeanContext beanContext) {
        this.beanContext = beanContext;
    }

    /**
     * The index of the given container.
     *
     * @param beanContext The context of the container
     * @return Its index
     */
    public static RecordedTypeIndex of(BeanContext beanContext) {
        return beanContext.getBean(RecordedTypeIndex.class);
    }

    /**
     * The module of the same container that reads classes, which answers for a class the application was not
     * compiled with.
     *
     * @return The module, or {@code null} where it is not on the classpath
     */
    io.micronaut.cdi.spi.@Nullable CdiReflection reflection() {
        return beanContext.findBean(io.micronaut.cdi.spi.CdiReflection.class).orElse(null);
    }

    /**
     * What was recorded of the class of the given name.
     *
     * @param className The class name
     * @return The record, or {@code null} where the application was not compiled with the class
     */
    @Nullable Entry of(String className) {
        // a bean of the specification records its own type closure on its definition, the class over its own variables
        // first, which is what the index would record of its class; the index holds the classes no definition describes
        Entry bean = beans().get(className);
        if (bean != null) {
            return bean;
        }
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
     * What the definitions of the classes that are beans recorded of them: the type closure of the class, which a
     * definition of a producer records of what it produces rather than of a class. The definitions are not loaded:
     * their references carry the metadata.
     */
    private Map<String, Entry> beans() {
        Map<String, Entry> resolved = beans;
        if (resolved == null) {
            resolved = new HashMap<>();
            for (io.micronaut.inject.BeanDefinitionReference<?> reference : beanContext.getBeanDefinitionReferences()) {
                io.micronaut.core.annotation.AnnotationMetadata metadata = reference.getAnnotationMetadata();
                if (metadata.hasAnnotation(io.micronaut.cdi.internal.metadata.CdiProducer.class)) {
                    continue;
                }
                List<AnnotationValue<Annotation>> closure = metadata
                    .findAnnotation(io.micronaut.cdi.internal.metadata.CdiBeanTypes.class)
                    .map(types -> types.getAnnotations("value")).orElse(List.of());
                if (closure.isEmpty()) {
                    continue;
                }
                AnnotationValue<Annotation> own = closure.get(0);
                String name = own.stringValue().orElse(null);
                if (name == null || "VARIABLE".equals(own.stringValue("kind").orElse(null))) {
                    continue;
                }
                List<String> variables = new ArrayList<>();
                for (AnnotationValue<Annotation> argument : own.getAnnotations(io.micronaut.cdi.internal.metadata.CdiRecordedType.ARGUMENTS)) {
                    if ("VARIABLE".equals(argument.stringValue("kind").orElse(null))) {
                        variables.add(argument.stringValue("name").orElse("T"));
                    }
                }
                resolved.putIfAbsent(name, new Entry(List.copyOf(variables), closure.subList(1, closure.size())));
            }
            beans = resolved;
        }
        return resolved;
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
