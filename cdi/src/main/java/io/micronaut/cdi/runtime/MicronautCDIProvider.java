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
package io.micronaut.cdi.runtime;

import jakarta.enterprise.inject.spi.CDI;
import jakarta.enterprise.inject.spi.CDIProvider;

/**
 * Hands the static entry point of the specification the container that is running.
 *
 * <p>It is found the way the specification says a provider is found, through the service loader, so that a
 * program which calls {@code CDI.current()} reaches the container of this module without naming it.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
public final class MicronautCDIProvider implements CDIProvider {

    @Override
    public CDI<Object> getCDI() {
        CdiBeanContainer container = CdiRunning.current();
        if (container == null) {
            throw new IllegalStateException("No container is running. A container of this module is a Micronaut "
                + "bean context, and CDI.current() resolves to one only while it is running");
        }
        return new MicronautCDI(container);
    }
}
