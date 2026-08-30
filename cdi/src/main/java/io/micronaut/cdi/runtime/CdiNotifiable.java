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
package io.micronaut.cdi.runtime;

import io.micronaut.core.annotation.Internal;
import jakarta.enterprise.inject.spi.EventMetadata;

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
}
