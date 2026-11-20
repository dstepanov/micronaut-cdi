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

import io.micronaut.cdi.lang.model.ast.ElementAnnotationInfo;
import io.micronaut.cdi.lang.model.ast.SourceModel;
import io.micronaut.cdi.processor.Cdi;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import jakarta.enterprise.inject.build.compatible.spi.ScopeInfo;
import jakarta.enterprise.inject.build.compatible.spi.StereotypeInfo;
import jakarta.enterprise.lang.model.AnnotationInfo;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.OptionalInt;

/**
 * A stereotype a bean declares, read off the annotation type as it was compiled: the scope, interceptor bindings,
 * alternative, reserve, priority, eager and auto-close initialization and default name it declares (section 2.7.1).
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
final class ElementStereotypeInfo implements StereotypeInfo {

    private static final String SCOPE = "jakarta.inject.Scope";
    private static final String INTERCEPTOR_BINDING = "jakarta.interceptor.InterceptorBinding";
    private static final String NAMED = "jakarta.inject.Named";

    private final String name;
    private final AnnotationMetadata metadata;
    private final SourceModel context;

    /**
     * @param name     The name of the stereotype annotation
     * @param metadata The annotations the stereotype annotation type declares
     * @param context  The source model
     */
    ElementStereotypeInfo(String name, AnnotationMetadata metadata, SourceModel context) {
        this.name = name;
        this.metadata = metadata;
        this.context = context;
    }

    @Override
    public @Nullable ScopeInfo defaultScope() {
        for (String scope : metadata.getAnnotationNamesByStereotype(Cdi.NORMAL_SCOPE)) {
            if (isOfTheSpecification(scope) && metadata.hasDeclaredAnnotation(scope)) {
                return new ElementScopeInfo(scope, true);
            }
        }
        for (String scope : metadata.getAnnotationNamesByStereotype(SCOPE)) {
            if (isOfTheSpecification(scope) && metadata.hasDeclaredAnnotation(scope)) {
                return new ElementScopeInfo(scope, false);
            }
        }
        return null;
    }

    @Override
    public Collection<AnnotationInfo> interceptorBindings() {
        List<AnnotationInfo> bindings = new ArrayList<>();
        for (String binding : metadata.getAnnotationNamesByStereotype(INTERCEPTOR_BINDING)) {
            if (!isOfTheSpecification(binding) || !metadata.hasDeclaredAnnotation(binding)) {
                continue;
            }
            for (AnnotationValue<Annotation> value : metadata.getAnnotationValuesByName(binding)) {
                bindings.add(new ElementAnnotationInfo(value, context));
            }
        }
        return bindings;
    }

    @Override
    public boolean isAlternative() {
        return metadata.hasDeclaredAnnotation(Cdi.ALTERNATIVE);
    }

    @Override
    public boolean isReserve() {
        return metadata.hasDeclaredAnnotation(Cdi.RESERVE);
    }

    @Override
    public @Nullable Integer priority() {
        OptionalInt priority = metadata.intValue(Cdi.PRIORITY, AnnotationMetadata.VALUE_MEMBER);
        return metadata.hasDeclaredAnnotation(Cdi.PRIORITY) && priority.isPresent() ? priority.getAsInt() : null;
    }

    @Override
    public boolean isEager() {
        return metadata.hasDeclaredAnnotation(Cdi.EAGER);
    }

    @Override
    public boolean isAutoClose() {
        return metadata.hasDeclaredAnnotation(Cdi.AUTO_CLOSE);
    }

    @Override
    public boolean isNamed() {
        return metadata.hasDeclaredAnnotation(NAMED);
    }

    /**
     * Whether the annotation is one the application or the specification wrote, rather than one Micronaut
     * materialized from it.
     */
    private static boolean isOfTheSpecification(String annotation) {
        return !annotation.startsWith("io.micronaut.");
    }

    @Override
    public String toString() {
        return "@" + name;
    }
}
