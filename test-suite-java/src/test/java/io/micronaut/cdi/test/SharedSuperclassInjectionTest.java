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
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * The injection points a bean of the specification inherits are marked for the container as it is visited, and the
 * mark stays with it: Micronaut keeps a mark written on an inherited field or parameter by the class it was written
 * through, so a bean of Micronaut's own that extends the same class keeps Micronaut's aggregation for its list.
 */
class SharedSuperclassInjectionTest {

    public interface Tool {
    }

    @Dependent
    public static class Hammer implements Tool {
    }

    @Dependent
    public static class Saw implements Tool {
    }

    @Dependent
    public static class Toolbox {
        @jakarta.enterprise.inject.Produces
        List<Tool> tools() {
            return List.of();
        }
    }

    public abstract static class Workshop {
        @Inject
        Hammer hammer;
        List<Tool> tools;

        @Inject
        void setTools(List<Tool> tools) {
            this.tools = tools;
        }
    }

    @Dependent
    public static class SpecificationWorkshop extends Workshop {
    }

    @Singleton
    public static class MicronautWorkshop extends Workshop {
    }

    @Test
    void aBeanOfMicronautsOwnKeepsMicronautsAggregationForThePointsOfASharedSuperclass() {
        try (ApplicationContext context = ApplicationContext.run()) {
            MicronautWorkshop micronaut = context.getBean(MicronautWorkshop.class);
            assertInstanceOf(Hammer.class, micronaut.hammer);
            assertEquals(2, micronaut.tools.size(), micronaut.tools::toString);
            SpecificationWorkshop specification = context.getBean(SpecificationWorkshop.class);
            assertInstanceOf(Hammer.class, specification.hammer);
            assertEquals(0, specification.tools.size(), specification.tools::toString);
        }
    }
}
