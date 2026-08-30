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

import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import jakarta.enterprise.context.spi.AlterableContext;
import jakarta.enterprise.inject.build.compatible.spi.ClassConfig;
import jakarta.enterprise.inject.build.compatible.spi.MetaAnnotations;
import jakarta.enterprise.inject.build.compatible.spi.ScannedClasses;
import jakarta.enterprise.lang.model.AnnotationInfo;
import jakarta.enterprise.lang.model.declarations.ClassInfo;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * What the discovery phase of section 2.10.2 said, kept until the classes it is about are compiled.
 *
 * <p>The phase says two kinds of thing, and neither is about a class that is in front of the compiler at the
 * time. A class added to the scanned ones is a bean even though nothing on it says so, and an annotation
 * registered as a qualifier, an interceptor binding or a stereotype is one even though it is not annotated as
 * one. Both are recorded here, by name, and applied when the class they name comes past.</p>
 *
 * <p>That is also the limit of it: a class named here is only reached if it is compiled by the build the
 * extension is running in. An annotation from a library that is already compiled cannot be told that it is a
 * qualifier, because there is no moment at which to tell it.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class DiscoveredClasses implements ScannedClasses, MetaAnnotations {

    private final Map<String, Map<String, List<AnnotationValue<?>>>> memberAnnotations =
        new java.util.LinkedHashMap<>();
    private final java.util.Set<String> scanned = new java.util.LinkedHashSet<>();
    private final java.util.Set<String> registeredQualifiers = new java.util.LinkedHashSet<>();
    private final Map<String, List<String>> contexts = new java.util.LinkedHashMap<>();
    private final Map<String, Boolean> normalContexts = new java.util.LinkedHashMap<>();
    private final Map<String, List<AnnotationValue<?>>> metaAnnotations = new LinkedHashMap<>();

    /**
     * The annotations the extensions made qualifiers of.
     *
     * @return The annotation names
     */
    public java.util.Set<String> registeredQualifiers() {
        java.util.Set<String> entries = new java.util.LinkedHashSet<>();
        for (String qualifier : registeredQualifiers) {
            java.util.List<String> nonbinding = new ArrayList<>();
            for (Map.Entry<String, List<AnnotationValue<?>>> member
                : memberAnnotationsFor(qualifier).entrySet()) {
                for (AnnotationValue<?> annotation : member.getValue()) {
                    if ("jakarta.enterprise.util.Nonbinding".equals(annotation.getAnnotationName())) {
                        nonbinding.add(member.getKey());
                    }
                }
            }
            entries.add(qualifier + "|" + String.join(";", nonbinding));
        }
        return entries;
    }

    /**
     * Whether the discovery phase added the class to the scanned ones, making it a bean even though nothing on
     * it says so.
     *
     * @param className The name of a class
     * @return Whether it was added
     */
    public boolean isScanned(String className) {
        return scanned.contains(className);
    }

    /**
     * The classes the discovery phase added to the scanned ones.
     *
     * @return The class names
     */
    public java.util.Set<String> scannedClasses() {
        return scanned;
    }

    /**
     * The contexts the extensions registered, one entry per scope in the record form the runtime reads:
     * {@code scopeAnnotationName|normal|contextClass1;contextClass2}.
     *
     * @return The entries
     */
    public List<String> contextRecords() {
        List<String> records = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : contexts.entrySet()) {
            records.add(entry.getKey() + "|" + normalContexts.getOrDefault(entry.getKey(), false) + "|"
                + String.join(";", entry.getValue()));
        }
        return records;
    }

    @Override
    public void add(String className) {
        // section 2.10.1: an added class is discovered as though it had a bean defining annotation
        scanned.add(className);
    }

    @Override
    public ClassConfig addQualifier(Class<? extends Annotation> annotation) {
        return register(annotation, "jakarta.inject.Qualifier");
    }

    @Override
    public ClassConfig addInterceptorBinding(Class<? extends Annotation> annotation) {
        return register(annotation, "jakarta.interceptor.InterceptorBinding");
    }

    @Override
    public ClassConfig addStereotype(Class<? extends Annotation> annotation) {
        return register(annotation, "jakarta.enterprise.inject.Stereotype");
    }

    @Override
    public void addContext(Class<? extends Annotation> scopeAnnotation,
                           boolean isNormal,
                           Class<? extends AlterableContext> contextClass) {
        registerContext(scopeAnnotation, isNormal, contextClass);
    }

    @Override
    public void addContext(Class<? extends Annotation> scopeAnnotation,
                           Class<? extends AlterableContext> contextClass) {
        boolean isNormal = scopeAnnotation.isAnnotationPresent(jakarta.enterprise.context.NormalScope.class);
        registerContext(scopeAnnotation, isNormal, contextClass);
    }

    /**
     * Registers a scope of the extension's own, and the context that holds its instances: the scope annotation
     * is given what makes the classes carrying it beans of a Micronaut custom scope — proxied, for a normal
     * one — and which context class serves it is recorded for the runtime to read (section 2.10.1).
     */
    private void registerContext(Class<? extends Annotation> scopeAnnotation, boolean isNormal,
                                 Class<? extends AlterableContext> contextClass) {
        String scopeName = scopeAnnotation.getName();
        List<String> contextsOfScope = contexts.computeIfAbsent(scopeName, name -> new ArrayList<>());
        contextsOfScope.add(contextClass.getName());
        if (contextsOfScope.size() > 1) {
            // a second context of the same scope: the recording already exists, and annotationsFor rebuilds it
            return;
        }
        List<AnnotationValue<?>> annotations = metaAnnotations
            .computeIfAbsent(scopeName, name -> new ArrayList<>());
        annotations.add(AnnotationValue.builder("jakarta.inject.Scope").build());
        if (isNormal) {
            annotations.add(AnnotationValue.builder("io.micronaut.runtime.context.scope.ScopedProxy").build());
        }
        annotations.add(AnnotationValue.builder("io.micronaut.cdi.annotation.CdiScope")
            .value(scopeName)
            .member("normal", isNormal)
            .build());
        normalContexts.put(scopeName, isNormal);
    }

    /**
     * The annotations to put on the given class, which the discovery phase said belong on it.
     *
     * @param className The name of the class
     * @return The annotations, which may be empty
     */
    public List<AnnotationValue<?>> annotationsFor(String className) {
        List<AnnotationValue<?>> annotations = metaAnnotations.getOrDefault(className, List.of());
        List<String> contextsOfScope = contexts.get(className);
        if (contextsOfScope == null) {
            return annotations;
        }
        List<AnnotationValue<?>> all = new ArrayList<>(annotations);
        all.add(AnnotationValue.builder("io.micronaut.cdi.annotation.CdiExtensionContext")
            .values(contextsOfScope.toArray(new String[0]))
            .member("scopeAnnotation", className)
            .member("normal", normalContexts.getOrDefault(className, false))
            .build());
        return all;
    }

    /**
     * Whether the phase said anything at all.
     *
     * @return Whether there is anything to apply
     */
    public boolean isEmpty() {
        return metaAnnotations.isEmpty() && scanned.isEmpty();
    }

    private ClassConfig register(Class<? extends Annotation> annotation, String metaAnnotation) {
        if ("jakarta.inject.Qualifier".equals(metaAnnotation)) {
            registeredQualifiers.add(annotation.getName());
        }
        List<AnnotationValue<?>> annotations = metaAnnotations
            .computeIfAbsent(annotation.getName(), name -> new ArrayList<>());
        annotations.add(AnnotationValue.builder(metaAnnotation).build());
        return new Deferred(annotation.getName(), annotations, annotation,
            memberAnnotations.computeIfAbsent(annotation.getName(), name -> new java.util.LinkedHashMap<>()));
    }

    /**
     * The annotations to put on the members of the given class, method by method.
     *
     * @param className The name of the class
     * @return What to add, keyed by member name
     */
    public Map<String, List<AnnotationValue<?>>> memberAnnotationsFor(String className) {
        return memberAnnotations.getOrDefault(className, Map.of());
    }

    /**
     * The names of every class the discovery phase said something about: the annotations it registered and
     * the scope annotations it gave contexts.
     *
     * @return The class names
     */
    public java.util.Set<String> describedClassNames() {
        java.util.Set<String> names = new java.util.LinkedHashSet<>(metaAnnotations.keySet());
        names.addAll(memberAnnotations.keySet());
        names.addAll(contexts.keySet());
        return names;
    }

    /**
     * The annotation an extension registered, which it may say more about. What it says is kept with the rest
     * and applied at the same moment.
     *
     * @param className         The name of the annotation the extension registered
     * @param annotations       What is to be put on it once the class comes past
     * @param annotationClass   The annotation class the extension handed in
     * @param memberAnnotations What is to be put on each named member once the class comes past
     */
    private record Deferred(String className, List<AnnotationValue<?>> annotations,
                            Class<? extends Annotation> annotationClass,
                            Map<String, List<AnnotationValue<?>>> memberAnnotations) implements ClassConfig {

        @Override
        public ClassInfo info() {
            throw new UnsupportedOperationException("The annotation " + className + " is registered by name "
                + "during discovery, and is not read until the class itself is compiled");
        }

        @Override
        public ClassConfig addAnnotation(Class<? extends Annotation> annotationType) {
            annotations.add(AnnotationValue.builder(annotationType.getName()).build());
            return this;
        }

        @Override
        public ClassConfig addAnnotation(AnnotationInfo annotation) {
            if (annotation instanceof ElementAnnotationInfo info) {
                annotations.add(info.annotationValue());
            } else {
                annotations.add(AnnotationValue.builder(annotation.name()).build());
            }
            return this;
        }

        @Override
        public ClassConfig addAnnotation(Annotation annotation) {
            annotations.add(ExtensionAnnotationValues.of(annotation));
            return this;
        }

        @Override
        public ClassConfig removeAnnotation(Predicate<AnnotationInfo> predicate) {
            throw new UnsupportedOperationException("An annotation cannot be taken off a class that has not been "
                + "compiled yet");
        }

        @Override
        public ClassConfig removeAllAnnotations() {
            throw new UnsupportedOperationException("An annotation cannot be taken off a class that has not been "
                + "compiled yet");
        }

        @Override
        public Collection<jakarta.enterprise.inject.build.compatible.spi.MethodConfig> constructors() {
            throw membersAreNotRead();
        }

        @Override
        public Collection<jakarta.enterprise.inject.build.compatible.spi.MethodConfig> methods() {
            // the annotation is registered during discovery, before anything is compiled, so its members are
            // read from the class itself: what an extension adds to them is recorded and applied when the
            // annotation type comes past the compiler
            List<jakarta.enterprise.inject.build.compatible.spi.MethodConfig> configs = new ArrayList<>();
            for (java.lang.reflect.Method member : annotationClass.getDeclaredMethods()) {
                configs.add(new DeferredMethod(member.getName(),
                    memberAnnotations.computeIfAbsent(member.getName(), name -> new ArrayList<>())));
            }
            return configs;
        }

        @Override
        public Collection<jakarta.enterprise.inject.build.compatible.spi.FieldConfig> fields() {
            throw membersAreNotRead();
        }

        private UnsupportedOperationException membersAreNotRead() {
            return new UnsupportedOperationException("The members of " + className + " are not read until the "
                + "class itself is compiled");
        }
    }

    /**
     * A member of an annotation registered during discovery: what an extension adds to it is recorded here and
     * put on the member when the annotation type is compiled.
     *
     * @param name        The member's name
     * @param annotations What to add to it
     */
    private record DeferredMethod(String name, List<AnnotationValue<?>> annotations)
        implements jakarta.enterprise.inject.build.compatible.spi.MethodConfig {

        @Override
        public jakarta.enterprise.lang.model.declarations.MethodInfo info() {
            return new DeferredMethodInfo(name);
        }

        @Override
        public jakarta.enterprise.inject.build.compatible.spi.MethodConfig addAnnotation(
            Class<? extends Annotation> annotationType) {
            annotations.add(AnnotationValue.builder(annotationType.getName()).build());
            return this;
        }

        @Override
        public jakarta.enterprise.inject.build.compatible.spi.MethodConfig addAnnotation(AnnotationInfo annotation) {
            if (annotation instanceof ElementAnnotationInfo info) {
                annotations.add(info.annotationValue());
            } else {
                annotations.add(AnnotationValue.builder(annotation.name()).build());
            }
            return this;
        }

        @Override
        public jakarta.enterprise.inject.build.compatible.spi.MethodConfig addAnnotation(Annotation annotation) {
            annotations.add(ExtensionAnnotationValues.of(annotation));
            return this;
        }

        @Override
        public jakarta.enterprise.inject.build.compatible.spi.MethodConfig removeAnnotation(
            Predicate<AnnotationInfo> predicate) {
            throw new UnsupportedOperationException("An annotation cannot be taken off a member that has not "
                + "been compiled yet");
        }

        @Override
        public jakarta.enterprise.inject.build.compatible.spi.MethodConfig removeAllAnnotations() {
            throw new UnsupportedOperationException("An annotation cannot be taken off a member that has not "
                + "been compiled yet");
        }

        @Override
        public List<jakarta.enterprise.inject.build.compatible.spi.ParameterConfig> parameters() {
            return List.of();
        }
    }

    /**
     * What little of a member is known before it is compiled: its name, which is what an extension filters by.
     *
     * @param name The member's name
     */
    private record DeferredMethodInfo(String name) implements jakarta.enterprise.lang.model.declarations.MethodInfo {

        @Override
        public String name() {
            return name;
        }

        @Override
        public jakarta.enterprise.lang.model.types.Type returnType() {
            throw notCompiledYet();
        }

        @Override
        public jakarta.enterprise.lang.model.types.Type receiverType() {
            throw notCompiledYet();
        }

        @Override
        public List<jakarta.enterprise.lang.model.declarations.ParameterInfo> parameters() {
            return List.of();
        }

        @Override
        public List<jakarta.enterprise.lang.model.types.Type> throwsTypes() {
            return List.of();
        }

        @Override
        public List<jakarta.enterprise.lang.model.types.TypeVariable> typeParameters() {
            return List.of();
        }

        @Override
        public boolean isConstructor() {
            return false;
        }

        @Override
        public boolean isStatic() {
            return false;
        }

        @Override
        public boolean isAbstract() {
            return true;
        }

        @Override
        public boolean isFinal() {
            return false;
        }

        @Override
        public int modifiers() {
            return java.lang.reflect.Modifier.PUBLIC | java.lang.reflect.Modifier.ABSTRACT;
        }

        @Override
        public jakarta.enterprise.lang.model.declarations.ClassInfo declaringClass() {
            throw notCompiledYet();
        }

        @Override
        public boolean hasAnnotation(Class<? extends Annotation> annotationType) {
            return false;
        }

        @Override
        public boolean hasAnnotation(Predicate<AnnotationInfo> predicate) {
            return false;
        }

        @Override
        @SuppressWarnings("NullAway")
        public <T extends Annotation> AnnotationInfo annotation(Class<T> annotationType) {
            return null;
        }

        @Override
        public <T extends Annotation> Collection<AnnotationInfo> repeatableAnnotation(Class<T> annotationType) {
            return List.of();
        }

        @Override
        public Collection<AnnotationInfo> annotations(Predicate<AnnotationInfo> predicate) {
            return List.of();
        }

        @Override
        public Collection<AnnotationInfo> annotations() {
            return List.of();
        }

        private UnsupportedOperationException notCompiledYet() {
            return new UnsupportedOperationException("The member " + name + " is not read until its class is "
                + "compiled");
        }
    }

}
