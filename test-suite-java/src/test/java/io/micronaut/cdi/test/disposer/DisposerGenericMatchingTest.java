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
package io.micronaut.cdi.test.disposer;

import io.micronaut.context.ApplicationContext;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.enterprise.util.TypeLiteral;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A disposer disposes of what a producer produces by the type the produced class has as the disposed parameter's
 * class: its own type arguments are projected onto that supertype before they are compared, so a class that
 * swaps them is disposed of as the supertype it is, and a class with none of its own is not disposed of as a
 * parameterization of its supertype it is not.
 */
class DisposerGenericMatchingTest {

    static final List<String> DISPOSED = Collections.synchronizedList(new ArrayList<>());

    static class Flipped<A, B> extends HashMap<B, A> {
        final String name;

        Flipped(String name) {
            this.name = name;
        }
    }

    static class Strings extends ArrayList<String> {
    }

    @Dependent
    public static class FlippedMaker {
        @Produces
        Flipped<String, Integer> flipped() {
            return new Flipped<>("flipped");
        }

        void dispose(@Disposes Map<Integer, String> map) {
            DISPOSED.add(map instanceof Flipped<?, ?> flipped ? flipped.name : "map");
        }
    }

    @Dependent
    public static class StringsMaker {
        @Produces
        Strings strings() {
            return new Strings();
        }

        @Produces
        List<Integer> numbers() {
            return new ArrayList<>(List.of(1));
        }

        void dispose(@Disposes List<Integer> numbers) {
            DISPOSED.add(((Object) numbers) instanceof Strings ? "strings" : "numbers");
        }
    }

    private static <T> void createAndDestroy(TypeLiteral<T> type) {
        Instance.Handle<T> handle = CDI.current().select(type).getHandle();
        handle.get();
        handle.destroy();
    }

    @Test
    void theProducedTypeIsProjectedOntoTheDisposedClassBeforeItsArgumentsAreCompared() {
        DISPOSED.clear();
        try (ApplicationContext ignored = ApplicationContext.run()) {
            createAndDestroy(new TypeLiteral<Flipped<String, Integer>>() { });
            createAndDestroy(new TypeLiteral<Strings>() { });
            createAndDestroy(new TypeLiteral<List<Integer>>() { });
        }
        assertEquals(List.of("flipped", "numbers"), DISPOSED);
    }
}
