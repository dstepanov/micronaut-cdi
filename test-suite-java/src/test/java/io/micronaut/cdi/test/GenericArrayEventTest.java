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

import io.micronaut.cdi.internal.runtime.CdiTypes;
import io.micronaut.cdi.internal.runtime.RecordedTypeIndex;
import io.micronaut.cdi.internal.type.SpecificationTypes;
import io.micronaut.context.ApplicationContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.spi.EventMetadata;
import jakarta.enterprise.util.TypeLiteral;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * A type variable of the class of an event that its supertype uses as the component of an array is resolved from
 * the type the event was fired as through the array: from what the processor recorded, and by the reflection
 * module for a class nothing was recorded of.
 */
class GenericArrayEventTest {

    public interface Payload<X> {
    }

    public static class ArrayPayload<T> implements Payload<T[]> {
    }

    public static class ListArrayPayload<T> implements Payload<List<T>[]> {
    }

    @ApplicationScoped
    public static class Receiver {
        final List<Type> heard = new ArrayList<>();

        List<Type> heard() {
            return List.copyOf(heard);
        }

        void strings(@Observes Payload<String[]> payload, EventMetadata metadata) {
            heard.add(metadata.getType());
        }
    }

    @jakarta.enterprise.context.Dependent
    public static class Sender {
        @Inject
        Event<Payload<String[]>> strings;
    }

    @Test
    void aRecordedVariableUsedAsTheComponentOfAnArrayIsResolvedThroughTheArray() {
        try (ApplicationContext context = ApplicationContext.run()) {
            context.getBean(Sender.class).strings.fire(new ArrayPayload<>());
            List<Type> heard = context.getBean(Receiver.class).heard();
            assertEquals(1, heard.size(), heard::toString);
            ParameterizedType type = assertInstanceOf(ParameterizedType.class, heard.get(0));
            assertEquals(ArrayPayload.class, type.getRawType());
            assertEquals(String.class, type.getActualTypeArguments()[0]);
        }
    }

    @Test
    void theContainerResolvesAVariableThroughAnArray() {
        // one inference for a class the application was compiled with and one the reflection module reads
        try (ApplicationContext context = ApplicationContext.run()) {
            RecordedTypeIndex index = RecordedTypeIndex.of(context);
            ParameterizedType strings = assertInstanceOf(ParameterizedType.class, CdiTypes.eventTypeOf(index,
                ArrayPayload.class, SpecificationTypes.argumentOf(new TypeLiteral<Payload<String[]>>() { }.getType()))
                .asType());
            assertEquals(String.class, strings.getActualTypeArguments()[0]);
            ParameterizedType lists = assertInstanceOf(ParameterizedType.class, CdiTypes.eventTypeOf(index,
                ListArrayPayload.class,
                SpecificationTypes.argumentOf(new TypeLiteral<Payload<List<String>[]>>() { }.getType())).asType());
            assertEquals(String.class, lists.getActualTypeArguments()[0]);
        }
    }
}
