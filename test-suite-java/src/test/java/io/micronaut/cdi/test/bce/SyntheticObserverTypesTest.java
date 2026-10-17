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
package io.micronaut.cdi.test.bce;

import io.micronaut.annotation.processing.test.JavaParser;
import jakarta.enterprise.inject.spi.EventContext;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.Parameters;
import jakarta.enterprise.inject.build.compatible.spi.Synthesis;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticComponents;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticObserver;
import jakarta.enterprise.inject.build.compatible.spi.Types;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A synthetic observer may observe a parameterized type whose argument is a wildcard or a type variable, as an
 * observer method may (section 10.4): the type is recorded as the type of a compiled one is.
 */
public class SyntheticObserverTypesTest {

    @Test
    void aSyntheticObserverOfAWildcardTypeIsRecorded() {
        Observing.added = false;
        try (JavaParser parser = ExtensionCompilation.of(Observing.class)) {
            parser.generate("observed.Subject", """
                package observed;

                @jakarta.enterprise.context.Dependent
                public class Subject {
                }
                """);
        }
        assertTrue(Observing.added, "The synthesis phase has to have run");
    }

    public static class Observing implements BuildCompatibleExtension {
        static volatile boolean added;

        @Synthesis
        public void synthesise(SyntheticComponents components, Types types) {
            components.<List<? extends Number>>addObserver(
                    types.parameterized(List.class, types.wildcardWithUpperBound(types.of(Number.class))))
                .observeWith(Numbers.class);
            added = true;
        }
    }

    public static class Numbers implements SyntheticObserver<List<? extends Number>> {
        @Override
        public void observe(EventContext<List<? extends Number>> event, Parameters params) {
        }
    }
}
