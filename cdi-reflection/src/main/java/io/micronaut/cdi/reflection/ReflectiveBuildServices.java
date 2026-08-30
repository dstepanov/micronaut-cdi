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
package io.micronaut.cdi.reflection;

import io.micronaut.core.annotation.Internal;
import jakarta.enterprise.inject.build.compatible.spi.AnnotationBuilder;
import jakarta.enterprise.inject.build.compatible.spi.AnnotationBuilderFactory;
import jakarta.enterprise.inject.build.compatible.spi.BuildServices;
import jakarta.enterprise.lang.model.declarations.ClassInfo;

import java.lang.annotation.Annotation;

/**
 * The build services of section 2.10, served reflectively: what {@code AnnotationBuilder.of} reaches for.
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class ReflectiveBuildServices implements BuildServices {

    @Override
    public AnnotationBuilderFactory annotationBuilderFactory() {
        return new Factory();
    }

    @Override
    public int getPriority() {
        return 0;
    }

    private static final class Factory implements AnnotationBuilderFactory {

        @Override
        public AnnotationBuilder create(Class<? extends Annotation> annotationType) {
            return new ReflectiveAnnotationBuilder(annotationType);
        }

        @SuppressWarnings("unchecked")
        @Override
        public AnnotationBuilder create(ClassInfo annotationType) {
            try {
                return new ReflectiveAnnotationBuilder((Class<? extends Annotation>) Class.forName(
                    annotationType.name(), false, Thread.currentThread().getContextClassLoader() != null
                        ? Thread.currentThread().getContextClassLoader()
                        : ReflectiveBuildServices.class.getClassLoader()));
            } catch (ClassNotFoundException e) {
                throw new IllegalArgumentException("The annotation " + annotationType.name()
                    + " is not loadable here", e);
            }
        }
    }
}
