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
package io.micronaut.cdi.runtime.extension;

import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.Internal;

/**
 * Tells the bean definitions generated for the classes an extension named — a creator, a disposer, a synthetic
 * observer, a context — from the beans of the application.
 *
 * <p>The container instantiates those classes through a definition so that it need not instantiate them
 * reflectively. The specification does not make them beans, and they are not reported as any: they are
 * infrastructure of the container that happens to be compiled the way a bean is.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class ExtensionComponents {

    private ExtensionComponents() {
    }

    /**
     * Whether the definition with the given metadata was generated for a class an extension named.
     *
     * @param metadata The metadata of a bean definition
     * @return Whether it is one of the container's own
     */
    public static boolean isComponent(AnnotationMetadata metadata) {
        return metadata.hasAnnotation("io.micronaut.cdi.annotation.CdiExtensionComponents")
            || metadata.hasAnnotation("io.micronaut.cdi.annotation.CdiSyntheticBean")
            || metadata.hasAnnotation("io.micronaut.cdi.annotation.CdiSyntheticDisposer")
            || metadata.hasAnnotation("io.micronaut.cdi.annotation.CdiSyntheticObserver")
            || metadata.hasAnnotation("io.micronaut.cdi.annotation.CdiRegisteredContext");
    }
}
