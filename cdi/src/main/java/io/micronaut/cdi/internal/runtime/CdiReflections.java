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
package io.micronaut.cdi.internal.runtime;

import io.micronaut.cdi.spi.CdiReflection;
import io.micronaut.core.annotation.Internal;

/**
 * Finds the {@link CdiReflection} of the running container for a call of the specification's API made where no
 * bean context is at hand: an annotation instance a qualifier is reported as, the member of an injection point.
 * The interface itself stays free of the container's internals; the running container is this module's to know.
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class CdiReflections {

    private CdiReflections() {
    }

    /**
     * The implementation of the running container.
     *
     * @param what What needs it, to open the message with when the module is absent
     * @return The implementation
     * @throws UnsupportedOperationException Where the module is not on the classpath
     */
    public static CdiReflection current(String what) {
        return CdiReflection.require(CdiRunning.currentContext(), what);
    }
}
