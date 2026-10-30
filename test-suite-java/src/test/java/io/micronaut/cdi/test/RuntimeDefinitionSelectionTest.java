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

import io.micronaut.cdi.internal.metadata.CdiScope;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.RuntimeBeanDefinition;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.inject.annotation.MutableAnnotationMetadata;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What an injection point was resolved to follows a definition registered with the bean context once the container
 * runs: a bean of the specification registered beside the one a point resolved to makes the point ambiguous for
 * the next bean created, as it does for a lookup.
 */
class RuntimeDefinitionSelectionTest {

    interface Lamp {
    }

    @Dependent
    static class DeskLamp implements Lamp {
    }

    static class SpareLamp implements Lamp {
    }

    @Dependent
    static class Desk {
        @Inject
        Lamp lamp;
    }

    @Test
    void aDefinitionRegisteredLaterIsSeenByTheNextResolutionOfAPoint() {
        try (ApplicationContext context = ApplicationContext.run()) {
            assertEquals(DeskLamp.class, context.getBean(Desk.class).lamp.getClass());
            MutableAnnotationMetadata metadata = new MutableAnnotationMetadata();
            metadata.addDeclaredAnnotation(CdiScope.class.getName(),
                Map.of(AnnotationMetadata.VALUE_MEMBER, Dependent.class.getName()));
            context.registerBeanDefinition(RuntimeBeanDefinition.builder(Lamp.class, SpareLamp::new)
                .annotationMetadata(metadata)
                .build());
            assertTrue(CDI.current().select(Lamp.class).isAmbiguous(), "a lookup sees both lamps");
            assertThrows(RuntimeException.class, () -> context.getBean(Desk.class),
                "the point of the desk is ambiguous now");
        }
    }
}
