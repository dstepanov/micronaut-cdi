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
package org.jboss.weld.resolution;

import io.micronaut.cdi.runtime.CdiAssignability;

import java.lang.reflect.Type;

/**
 * The rules by which an event type is assignable to an observed type (section 2.8.3), as this implementation
 * applies them: the type rules alone, without the qualifiers, and without what the container's API refuses of an
 * event type - Weld's rules answer for a type variable in an event type, where the API throws.
 */
public final class EventTypeAssignabilityRules implements AssignabilityRules {

    private static final EventTypeAssignabilityRules INSTANCE = new EventTypeAssignabilityRules();

    private EventTypeAssignabilityRules() {
    }

    public static EventTypeAssignabilityRules instance() {
        return INSTANCE;
    }

    @Override
    public boolean matches(Type observedType, Type eventType) {
        return CdiAssignability.isEventTypeMatching(observedType, eventType);
    }
}
