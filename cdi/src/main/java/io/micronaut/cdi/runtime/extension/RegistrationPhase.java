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

import io.micronaut.core.annotation.Internal;
import io.micronaut.inject.BeanDefinition;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.Messages;

import java.util.List;

/**
 * The registration phase of section 2.10.4, where there is something to run it with.
 *
 * <p>The phase describes each bean to an extension in the terms of the language model, and for every bean the
 * compiler saw it has already run: the annotation processor describes a bean as it compiles it, which is where the
 * model costs nothing because it is what the compiler is holding anyway.</p>
 *
 * <p>What is left over is the beans the compiler never saw — the synthetic ones, which an extension described
 * rather than wrote. Describing those means reading their classes back with reflection, which is the one thing
 * Micronaut is built to avoid, so it is not here: it is in a module of its own that an application adds when it
 * wants it. This is the seam between them, and where no implementation is present the synthetic beans are simply
 * not described.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public interface RegistrationPhase {

    /**
     * Describes the given beans to the registration methods of the given extensions.
     *
     * @param extensions The extensions
     * @param beans      The beans to describe, which are the ones the compiler did not
     * @param messages   What the extensions have to say
     */
    void run(List<BuildCompatibleExtension> extensions, List<BeanDefinition<?>> beans, Messages messages);
}
