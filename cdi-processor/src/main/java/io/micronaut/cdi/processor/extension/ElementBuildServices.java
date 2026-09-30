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
import jakarta.enterprise.inject.build.compatible.spi.AnnotationBuilder;
import jakarta.enterprise.inject.build.compatible.spi.AnnotationBuilderFactory;
import jakarta.enterprise.inject.build.compatible.spi.BuildServices;
import jakarta.enterprise.lang.model.declarations.ClassInfo;

import java.lang.annotation.Annotation;

/**
 * The services the specification's build time API finds through the service loader (section 2.10): what
 * {@code AnnotationBuilder.of} composes an annotation with.
 *
 * <p>An extension runs while the application compiles, so this is on the classpath an extension runs on - the
 * annotation processor path - and what it composes is the annotation of the language model the compilation's
 * own view of the classes gives.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class ElementBuildServices implements BuildServices {

    @Override
    public AnnotationBuilderFactory annotationBuilderFactory() {
        return new AnnotationBuilderFactory() {
            @Override
            public AnnotationBuilder create(Class<? extends Annotation> annotationType) {
                return new ElementAnnotationBuilder(annotationType.getName());
            }

            @Override
            public AnnotationBuilder create(ClassInfo annotationType) {
                return new ElementAnnotationBuilder(annotationType.name());
            }
        };
    }

    @Override
    public int getPriority() {
        return 0;
    }
}
