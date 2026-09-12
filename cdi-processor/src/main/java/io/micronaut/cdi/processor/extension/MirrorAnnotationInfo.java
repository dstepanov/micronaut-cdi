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

import io.micronaut.core.annotation.Internal;
import io.micronaut.inject.ast.ClassElement;
import jakarta.enterprise.lang.model.AnnotationInfo;
import jakarta.enterprise.lang.model.AnnotationMember;
import jakarta.enterprise.lang.model.declarations.ClassInfo;
import org.jspecify.annotations.Nullable;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.util.Elements;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One annotation written on a declaration, read from the use of it the compiler recorded.
 *
 * <p>Every member is reported, the ones the use left to their default included: the language model describes an
 * annotation as an extension would find it at runtime, and a member not written there still has a value.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
final class MirrorAnnotationInfo implements AnnotationInfo {

    private final AnnotationMirror mirror;
    private final @Nullable Elements elements;

    MirrorAnnotationInfo(AnnotationMirror mirror, @Nullable Elements elements) {
        this.mirror = mirror;
        this.elements = elements;
    }

    @Override
    public ClassInfo declaration() {
        ClassElement declaration = ExtensionAnnotationTypes.declarationOf(name());
        if (declaration == null) {
            throw new IllegalStateException("The annotation interface " + name()
                + " is not on the compilation's classpath");
        }
        return new ElementClassInfo(declaration);
    }

    @Override
    public String name() {
        return ExtensionSourceModel.nameOf(mirror);
    }

    @Override
    public boolean hasMember(String name) {
        return member(name) != null;
    }

    @Override
    public @Nullable AnnotationMember member(String name) {
        for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> value : values().entrySet()) {
            if (name.contentEquals(value.getKey().getSimpleName())) {
                return new MirrorAnnotationMember(value.getValue(), elements);
            }
        }
        return null;
    }

    @Override
    public Map<String, AnnotationMember> members() {
        Map<String, AnnotationMember> members = new LinkedHashMap<>();
        values().forEach((member, value) ->
            members.put(member.getSimpleName().toString(), new MirrorAnnotationMember(value, elements)));
        return members;
    }

    private Map<? extends ExecutableElement, ? extends AnnotationValue> values() {
        return elements == null ? mirror.getElementValues() : elements.getElementValuesWithDefaults(mirror);
    }

    @Override
    public String toString() {
        return "@" + name();
    }
}
