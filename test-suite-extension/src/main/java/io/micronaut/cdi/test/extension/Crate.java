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

/**
 * An implementation a synthetic bean exposes as its own class alone: the interface the class implements is not
 * among the bean types, as the extension did not declare it.
 */
public final class Crate implements Runnable {

    @Override
    public void run() {
        // nothing to do: only the types of the bean are read
    }
}
