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
package io.micronaut.cdi.processor.mapper;

import io.micronaut.core.annotation.AnnotationUtil;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import io.micronaut.inject.annotation.AnnotationRemapper;
import io.micronaut.inject.ast.AnnotationMemberElement;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.visitor.VisitorContext;
import org.jspecify.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Records every member a qualifier or an interceptor binding declares a default for among the defaults of each
 * occurrence of it, the empty string included.
 *
 * <p>The metadata Micronaut writes leaves an empty string default out, to keep it small, so an occurrence that
 * leaves such a member at its default records nothing of the member at all. The container reads which members
 * a qualifier type has from the occurrences it was compiled with (the members they write and the defaults they
 * carry), and a type whose one member defaults to the empty string, used only with it left out, would read as a
 * type with no member: a lookup by another value of the member would then find the bean. The members are read
 * off the annotation interface ({@link io.micronaut.inject.ast.AnnotationElement#getMembers()}), and only for an
 * annotation whose interface is a qualifier or an interceptor binding; an occurrence that records every default
 * already is left as it is.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class EmptyDefaultsRemapper implements AnnotationRemapper {

    private static final String BINDING = "jakarta.interceptor.InterceptorBinding";

    @Override
    public String getPackageName() {
        return ALL_PACKAGES;
    }

    @Override
    public List<AnnotationValue<?>> remap(AnnotationValue<?> annotation, @Nullable VisitorContext visitorContext) {
        if (visitorContext == null || !isBindingType(annotation)
            || NonbindingMembers.isNeverABindingType(annotation.getAnnotationName())) {
            return List.of(annotation);
        }
        ClassElement declaration = NonbindingMembers.declarationOf(annotation.getAnnotationName(), visitorContext);
        if (declaration == null) {
            return List.of(annotation);
        }
        Map<CharSequence, Object> recorded = annotation.getDefaultValues();
        Map<CharSequence, Object> missing = null;
        for (AnnotationMemberElement member : NonbindingMembers.memberElementsOf(declaration).values()) {
            String name = member.getName();
            if ((recorded != null && recorded.containsKey(name)) || annotation.contains(name)) {
                continue;
            }
            Optional<Object> declared = member.getDefaultValue();
            if (declared.isPresent()) {
                if (missing == null) {
                    missing = new LinkedHashMap<>(2);
                }
                missing.put(name, declared.get());
            }
        }
        if (missing == null) {
            return List.of(annotation);
        }
        return List.of(annotation.mutate().defaultValues(missing).build());
    }

    /**
     * Whether the annotation interface is itself annotated as a qualifier or an interceptor binding, which the
     * occurrence carries among its stereotypes by the time it is remapped.
     */
    private static boolean isBindingType(AnnotationValue<?> annotation) {
        List<AnnotationValue<?>> stereotypes = annotation.getStereotypes();
        if (stereotypes == null) {
            return false;
        }
        for (AnnotationValue<?> stereotype : stereotypes) {
            String name = stereotype.getAnnotationName();
            if (AnnotationUtil.QUALIFIER.equals(name) || BINDING.equals(name)) {
                return true;
            }
        }
        return false;
    }
}
