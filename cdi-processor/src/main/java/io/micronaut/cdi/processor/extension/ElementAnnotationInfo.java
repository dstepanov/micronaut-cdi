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
import jakarta.enterprise.lang.model.AnnotationInfo;
import jakarta.enterprise.lang.model.AnnotationMember;
import jakarta.enterprise.lang.model.declarations.ClassInfo;
import org.jspecify.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One annotation written on a declaration, read from what Micronaut recorded for it.
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class ElementAnnotationInfo implements AnnotationInfo {

    private final AnnotationValue<?> annotation;

    ElementAnnotationInfo(AnnotationValue<?> annotation) {
        this.annotation = annotation;
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
        throw new IllegalStateException("The class that declares the annotation " + annotation.getAnnotationName()
            + " is not read here: an annotation is recorded by name and by the values written for it");
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
        for (Map.Entry<CharSequence, Object> member : annotation.getValues().entrySet()) {
            if (name.contentEquals(member.getKey())) {
                return new ElementAnnotationMember(member.getValue());
            }
        }
        return null;
    }

    @Override
    public Map<String, AnnotationMember> members() {
        Map<String, AnnotationMember> members = new LinkedHashMap<>();
        annotation.getValues().forEach((name, value) ->
            members.put(name.toString(), new ElementAnnotationMember(value)));
        return members;
    }

    @Override
    public String toString() {
        return "@" + annotation.getAnnotationName();
    }
}
