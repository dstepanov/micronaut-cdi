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

import io.micronaut.context.BeanContext;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.type.Argument;
import io.micronaut.core.type.GenericPlaceholder;
import io.micronaut.core.type.TypeHierarchies;
import io.micronaut.core.type.TypeHierarchy;
import jakarta.inject.Singleton;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The generic hierarchies of the classes the application was compiled with, as the processor recorded them:
 * what an event of a class is matched by and what the types of a bean are, read from compiled records rather than
 * from the class's generic signature, and held as the arguments the container works with.
 *
 * <p>A class is recorded as Micronaut's type hierarchy record of it ({@link TypeHierarchies}); a bean whose closure
 * such a record cannot say in full records its type closure on its definition instead. A class the application was
 * not compiled with has no record. The module that reads classes answers for it where it is there, and otherwise a
 * class is matched as its raw class and the raw classes above it.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Singleton
@Internal
public final class RecordedTypeIndex {

    private final BeanContext beanContext;
    private final Map<Class<?>, Optional<Entry>> hierarchies = new ConcurrentHashMap<>();
    /**
     * The bean types of each definition, worked out once from what was recorded: the container compares them for
     * every lookup it makes, and the resolution customizer for every lookup Micronaut makes of a bean of the
     * specification, and what the processor recorded does not change. A compiled definition is equal to another
     * of its class and a runtime one only to itself, which is what tells the beans apart.
     */
    private final Map<io.micronaut.inject.BeanDefinition<?>, List<Argument<?>>> beanTypes = new ConcurrentHashMap<>();

    public RecordedTypeIndex(BeanContext beanContext) {
        this.beanContext = beanContext;
    }

    /**
     * The bean types of the bean a definition describes ({@link CdiBean#typesOf}).
     *
     * @param definition The definition
     * @return The bean types
     */
    List<Argument<?>> beanTypesOf(io.micronaut.inject.BeanDefinition<?> definition) {
        List<Argument<?>> known = beanTypes.get(definition);
        if (known != null) {
            return known;
        }
        List<Argument<?>> types = List.copyOf(CdiBean.typesOf(this, definition, beanClassOf(definition)));
        List<Argument<?>> raced = beanTypes.putIfAbsent(definition, types);
        return raced != null ? raced : types;
    }

    /**
     * The class a bean was written as: for a bean in a normal scope, the class its client proxy stands in front of.
     *
     * @param definition The definition
     * @return The class
     */
    static Class<?> beanClassOf(io.micronaut.inject.BeanDefinition<?> definition) {
        return definition instanceof io.micronaut.inject.ProxyBeanDefinition<?> proxy
            ? proxy.getTargetType() : definition.getBeanType();
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
     * What was recorded of the given class.
     *
     * @param type The class
     * @return The record, or {@code null} where the application was not compiled with the class
     */
    @Nullable Entry of(Class<?> type) {
        Entry hierarchy = hierarchies.computeIfAbsent(type, this::recordOf).orElse(null);
        if (hierarchy instanceof HierarchyEntry entry && entry.hierarchy().getOmittedSuperTypeNames().isEmpty()) {
            return hierarchy;
        }
        // a bean whose type hierarchy record left a supertype out, or that has none, may record its closure on its
        // definition
        Entry bean = recordedOf(type);
        if (bean != null) {
            return bean;
        }
        // a record that left a supertype out says less than the module that reads classes, where it is there
        return hierarchy != null && reflection() != null ? null : hierarchy;
    }

    /**
     * Whether the type hierarchy record of the class says its whole closure: it was found, and left no supertype
     * out.
     *
     * @param type The class
     * @return Whether it does
     */
    boolean hasCompleteRecord(Class<?> type) {
        return hierarchies.computeIfAbsent(type, this::recordOf)
            .filter(entry -> entry instanceof HierarchyEntry hierarchy
                && hierarchy.hierarchy().getOmittedSuperTypeNames().isEmpty())
            .isPresent();
    }

    /**
     * The record of a class as the class loader of this container finds it, or the loader of the class where that one
     * finds none: a record of that very class, not of another loader's class of the same name. Two containers whose
     * loaders see different records of a class they share - a class of a parent loader, a record of each deployment -
     * each find their own, and what one found is kept by it alone ({@link #hierarchies}), so that no record, nor the
     * loader of a deployment it was found through, is kept with the shared class.
     */
    private Optional<Entry> recordOf(Class<?> type) {
        ClassLoader containerLoader = beanContext.getClassLoader();
        Optional<TypeHierarchy> found = findRecord(type, containerLoader);
        ClassLoader typeLoader = type.getClassLoader();
        if (found.isEmpty() && typeLoader != null && typeLoader != containerLoader) {
            found = findRecord(type, typeLoader);
        }
        return found.map(HierarchyEntry::new);
    }

    private static Optional<TypeHierarchy> findRecord(Class<?> type, @Nullable ClassLoader loader) {
        if (loader == null) {
            return Optional.empty();
        }
        return TypeHierarchies.find(type.getName(), loader).filter(hierarchy -> hierarchy.getType() == type);
    }

    /**
     * What the definition of the bean of the class recorded of it, found among the definitions the bean context
     * enumerates for the class.
     */
    private @Nullable Entry recordedOf(Class<?> type) {
        for (io.micronaut.inject.BeanDefinition<?> definition
            : beanContext.getBeanDefinitions(Argument.of(type))) {
            io.micronaut.core.annotation.AnnotationMetadata metadata = definition.getAnnotationMetadata();
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
            if (!type.getName().equals(name) || "VARIABLE".equals(own.stringValue("kind").orElse(null))) {
                continue;
            }
            List<String> variables = new ArrayList<>();
            for (AnnotationValue<Annotation> argument : own.getAnnotations(io.micronaut.cdi.internal.metadata.CdiRecordedType.ARGUMENTS)) {
                if ("VARIABLE".equals(argument.stringValue("kind").orElse(null))) {
                    variables.add(argument.stringValue("name").orElse("T"));
                }
            }
            return new RecordedEntry(List.copyOf(variables), closure.subList(1, closure.size()));
        }
        return null;
    }

    /**
     * What was recorded of one class.
     */
    sealed interface Entry permits RecordedEntry, HierarchyEntry {

        /**
         * @return The names of the type variables the class declares
         */
        List<String> variables();

        /**
         * The type closure of the class as the given type names it: the type, and every recorded supertype
         * with the variables of the class replaced by the type arguments given, {@code Object} left out.
         *
         * @param type The class, or a parameterization of it
         * @return The closure, or {@code null} where the record refers to a class that is not there
         */
        @Nullable List<Argument<?>> closureOf(Argument<?> type);

        /**
         * The class over its own type variables, which is the type a bean of a generic class has.
         *
         * @param type The class
         * @return The class, or its parameterization over its variables
         */
        Argument<?> declaredTypeOf(Class<?> type);
    }

    /**
     * Micronaut's type hierarchy record of a class.
     *
     * @param hierarchy The record
     */
    record HierarchyEntry(TypeHierarchy hierarchy) implements Entry {

        @Override
        public List<String> variables() {
            List<String> variables = new ArrayList<>();
            for (Argument<?> variable : hierarchy.getTypeVariables()) {
                variables.add(variable instanceof GenericPlaceholder<?> placeholder
                    ? placeholder.getVariableName() : variable.getName());
            }
            return variables;
        }

        @Override
        public List<Argument<?>> closureOf(Argument<?> type) {
            List<Argument<?>> closure = new ArrayList<>(hierarchy.getTypeClosure(type));
            // every closure ends in Object, which the reader adds once
            closure.removeIf(CdiTypes::isObject);
            return closure;
        }

        @Override
        public Argument<?> declaredTypeOf(Class<?> type) {
            return hierarchy.getDeclaration();
        }
    }

    /**
     * The type closure a definition recorded of the class of its bean.
     *
     * @param variables  The names of the type variables the class declares
     * @param supertypes The classes and interfaces above it, as the hierarchy parameterizes them
     */
    record RecordedEntry(List<String> variables, List<AnnotationValue<Annotation>> supertypes) implements Entry {

        @Override
        public @Nullable List<Argument<?>> closureOf(Argument<?> type) {
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

        @Override
        public Argument<?> declaredTypeOf(Class<?> type) {
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
