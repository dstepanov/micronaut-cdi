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
 * A class of a library that says nothing about itself, compiled without the processor, which the extension adds
 * to the scanned classes of every application compiled with it: no compilation of the application compiles it,
 * and it is a bean all the same, with its injection point and its producer.
 */
public class ScannedLibraryBean {

    /**
     * An injection point, which the container resolves as it resolves one of a class the application compiles.
     */
    @jakarta.inject.Inject
    public jakarta.enterprise.inject.spi.BeanManager manager;

    /**
     * @return What the bean produces
     */
    @jakarta.enterprise.inject.Produces
    @jakarta.enterprise.context.Dependent
    public ScannedLibraryProduct product() {
        return new ScannedLibraryProduct("produced by the library");
    }

    /**
     * @return What the bean says
     */
    public String name() {
        return "library";
    }
}
