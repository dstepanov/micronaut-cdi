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
package io.micronaut.cdi.test;

import io.micronaut.context.ApplicationContext;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A bean of the specification resolves the injection points it inherits from a class that is no bean of the
 * specification as the container resolves its own (section 4.2): a {@code List<Part>} is the bean of that type, not
 * Micronaut's aggregation of the parts. A bean of Micronaut's own that extends the same class keeps Micronaut's
 * aggregation for them.
 */
class InheritedInjectionPointTest {

    public interface Part {
    }

    @Dependent
    public static class Bolt implements Part {
    }

    @Dependent
    public static class Nut implements Part {
    }

    @Dependent
    public static class PartsProducer {
        @Produces
        List<Part> parts() {
            return List.of();
        }
    }

    public abstract static class Bench {
        @Inject
        List<Part> parts;
        List<Part> fromSetter;

        @Inject
        void setParts(List<Part> parts) {
            this.fromSetter = parts;
        }
    }

    @Dependent
    public static class SpecificationBench extends Bench {
    }

    @Singleton
    public static class MicronautBench extends Bench {
    }

    @Test
    void aBeanOfTheSpecificationResolvesTheInjectionPointsItInherits() {
        try (ApplicationContext context = ApplicationContext.run()) {
            SpecificationBench specification = context.getBean(SpecificationBench.class);
            assertEquals(0, specification.parts.size(), specification.parts::toString);
            assertEquals(0, specification.fromSetter.size(), specification.fromSetter::toString);
            MicronautBench micronaut = context.getBean(MicronautBench.class);
            assertEquals(2, micronaut.parts.size(), micronaut.parts::toString);
            assertEquals(2, micronaut.fromSetter.size(), micronaut.fromSetter::toString);
        }
    }
}
