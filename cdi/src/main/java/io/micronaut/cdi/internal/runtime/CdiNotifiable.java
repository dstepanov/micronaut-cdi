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
package io.micronaut.cdi.internal.runtime;

import io.micronaut.core.annotation.Internal;
import io.micronaut.core.type.Argument;
import jakarta.enterprise.inject.spi.EventMetadata;

import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * What the registry notifies: an observer method that takes the event and the metadata of its firing.
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public interface CdiNotifiable {

    /**
     * Notifies the observer of an event, with the metadata of the firing.
     *
     * @param event    The event
     * @param metadata What the observer may ask about the firing
     */
    void notifyWith(Object event, EventMetadata metadata);

    /**
     * Notifies the observer of an event, with the metadata of the firing, where it is still to be told once what it is
     * handed has been resolved: the creation of a parameter may take long enough for the container to have destroyed
     * meanwhile what the event announces.
     *
     * @param event       The event
     * @param metadata    What the observer may ask about the firing
     * @param stillToTell Whether it is still to be told, asked once its arguments are resolved
     */
    default void notifyWith(Object event, EventMetadata metadata, BooleanSupplier stillToTell) {
        if (stillToTell.getAsBoolean()) {
            notifyWith(event, metadata);
        }
    }

    /**
     * The qualifiers the observer observes, as resolution compares them.
     *
     * @return The observed qualifiers
     */
    List<CdiQualifier> observedQualifiers();

    /**
     * The type the observer observes, as resolution compares it.
     *
     * @return The observed type
     */
    Argument<?> observedArgument();
}
