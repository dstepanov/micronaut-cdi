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
package io.micronaut.cdi.lang.model.ast;

import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.visitor.VisitorContext;
import jakarta.enterprise.lang.model.AnnotationInfo;
import jakarta.enterprise.lang.model.AnnotationMember;
import jakarta.enterprise.lang.model.declarations.ClassInfo;
import org.jspecify.annotations.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An annotation, read from the values Micronaut recorded for it.
 *
 * <p>The members are the ones the use wrote and, for the rest, the defaults the annotation interface declares:
 * Micronaut records the defaults beside the written values, but leaves an empty string or array out, so the
 * interface is asked for those.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class ElementAnnotationInfo implements AnnotationInfo {

    private final AnnotationValue<?> annotation;
    private final @Nullable VisitorContext context;
    private @Nullable Map<CharSequence, Object> resolvedMembers;

    /**
     * @param annotation The values Micronaut recorded for the annotation
     * @param context    The compilation the annotation is read in, which is asked for its interface and the
     *                   defaults of its members
     */
    public ElementAnnotationInfo(AnnotationValue<?> annotation, VisitorContext context) {
        this(context, annotation);
    }

    private ElementAnnotationInfo(@Nullable VisitorContext context, AnnotationValue<?> annotation) {
        this.annotation = annotation;
        this.context = context;
    }

    /**
     * An annotation an extension composed with no compilation under way, which is the one place an annotation
     * of this model is made without one: its members are the ones it was given, and nothing that only the
     * compiler knows - its interface, the defaults it declares, the classes its members name - can be read of it.
     *
     * @param annotation The values the annotation was composed with
     * @return The annotation
     */
    public static ElementAnnotationInfo composedWithoutCompilation(AnnotationValue<?> annotation) {
        return new ElementAnnotationInfo(null, annotation);
    }

    /**
     * The values Micronaut recorded for the annotation.
     *
     * @return The annotation value
     */
    public AnnotationValue<?> annotationValue() {
        return annotation;
    }

    @Override
    public ClassInfo declaration() {
        // an annotation is recorded by name and by the values written for it, so the interface it names is asked
        // of the compiler rather than read off the record
        VisitorContext compilation = context;
        ClassElement declaration = compilation == null ? null
            : ExtensionAnnotationTypes.declarationOf(annotation.getAnnotationName(), compilation);
        if (compilation == null || declaration == null) {
            throw new IllegalStateException("The annotation interface " + annotation.getAnnotationName()
                + " is not on the compilation's classpath");
        }
        return new ElementClassInfo(declaration, compilation);
    }

    @Override
    public String name() {
        return annotation.getAnnotationName();
    }

    @Override
    public boolean hasMember(String name) {
        return member(name) != null;
    }

    @Override
    public @Nullable AnnotationMember member(String name) {
        for (Map.Entry<CharSequence, Object> member : values().entrySet()) {
            if (name.contentEquals(member.getKey())) {
                return new ElementAnnotationMember(member.getValue(), name(), name, context);
            }
        }
        return null;
    }

    @Override
    public Map<String, AnnotationMember> members() {
        Map<String, AnnotationMember> members = new LinkedHashMap<>();
        values().forEach((name, value) ->
            members.put(name.toString(), new ElementAnnotationMember(value, name(), name.toString(), context)));
        return Collections.unmodifiableMap(members);
    }

    private Map<CharSequence, Object> values() {
        if (resolvedMembers == null) {
            Map<CharSequence, Object> values = new LinkedHashMap<>(annotation.getValues());
            Map<CharSequence, Object> recorded = annotation.getDefaultValues();
            if (recorded != null) {
                recorded.forEach(values::putIfAbsent);
            }
            if (context != null) {
                context.getAnnotationDefaultValues(annotation.getAnnotationName()).forEach(values::putIfAbsent);
            }
            resolvedMembers = values;
        }
        return resolvedMembers;
    }

    @Override
    public String toString() {
        return "@" + annotation.getAnnotationName();
    }
}
