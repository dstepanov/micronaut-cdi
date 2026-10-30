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
     * What the bean observed and disposed of, for the tests to read, held by the container.
     */
    @jakarta.inject.Inject
    public ScannedLibraryProbe probe;

    /**
     * A qualified field that is no injection point: it keeps its value.
     */
    @jakarta.inject.Named("nothing.here")
    public String kept = "kept";

    /**
     * @param signal The signal observed
     */
    public void observe(@jakarta.enterprise.event.Observes ScannedLibrarySignal signal) {
        probe.saw("observed " + signal.name());
    }

    /**
     * @param product What the producer produced
     */
    public void dispose(@jakarta.enterprise.inject.Disposes ScannedLibraryProduct product) {
        probe.saw("disposed " + product.name());
    }

    /**
     * @return Every product, which the overload of {@link #note(ScannedLibraryProduct[])} is injected with
     */
    @jakarta.enterprise.inject.Produces
    public ScannedLibraryProduct[] products() {
        return new ScannedLibraryProduct[] {new ScannedLibraryProduct("one of several")};
    }

    /**
     * @param product A product
     * @return The note of one product
     */
    @jakarta.enterprise.inject.Produces
    @jakarta.inject.Named("single")
    public ScannedLibraryNote note(ScannedLibraryProduct product) {
        return new ScannedLibraryNote("single");
    }

    /**
     * @param products Several products
     * @return The note of several products, an overload told apart by the dimensions of its parameter
     */
    @jakarta.enterprise.inject.Produces
    @jakarta.inject.Named("several")
    public ScannedLibraryNote note(ScannedLibraryProduct[] products) {
        return new ScannedLibraryNote("several");
    }

    /**
     * @param note The note of several products
     */
    public void disposeSeveral(@jakarta.enterprise.inject.Disposes @jakarta.inject.Named("several")
                               ScannedLibraryNote note) {
        probe.saw("disposed note " + note.name());
    }

    /**
     * @return A primitive product, which Micronaut's bean builder leaves out before Micronaut Core #14217
     */
    @jakarta.enterprise.inject.Produces
    @jakarta.inject.Named("libraryCount")
    public int count() {
        return 3;
    }

    /**
     * @return A product of a package-private producer, which Micronaut's bean builder leaves out before Micronaut
     * Core #14217
     */
    @jakarta.enterprise.inject.Produces
    @jakarta.inject.Named("libraryHidden")
    String hidden() {
        return "hidden";
    }

    /**
     * @return What the bean says
     */
    public String name() {
        return "library";
    }
}
