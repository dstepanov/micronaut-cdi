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
package io.micronaut.cdi.test.extension;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * What the scanned class of a library observed and disposed of, held by the container that created it: one
 * instance per container, so that two containers do not mix what they saw.
 */
@jakarta.inject.Singleton
public class ScannedLibraryProbe {

    private final List<String> seen = new CopyOnWriteArrayList<>();

    /**
     * @param what What was seen
     */
    public void saw(String what) {
        seen.add(what);
    }

    /**
     * @return What was seen, in order
     */
    public List<String> seen() {
        return List.copyOf(seen);
    }
}
