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
package io.micronaut.cdi.reflection;

/**
 * What the events of the portable extension lifecycle have in common in a container whose beans were compiled
 * before it started: an event is only to be used while its observers are notified (section 3.9.5), and what an
 * observer asks of it that would change a compiled bean is refused.
 *
 * @author Denis Stepanov
 * @since 1.0
 */
abstract class PortableEvent {

    private final String name;
    private boolean notifying = true;

    PortableEvent(String name) {
        this.name = name;
    }

    /**
     * Ends the notification: the event answers nothing afterwards.
     */
    final void delivered() {
        notifying = false;
    }

    /**
     * Refuses a call made once the observers of the event have been notified.
     */
    final void whileNotifying() {
        if (!notifying) {
            throw new IllegalStateException(name + " is only to be used while its observers are notified");
        }
    }

    /**
     * What an operation that would change a compiled bean is refused with.
     *
     * @param operation The operation, as the interface names it
     * @return The exception to throw
     */
    final UnsupportedOperationException refused(String operation) {
        return refused(name, operation);
    }

    static UnsupportedOperationException refused(String event, String operation) {
        return new UnsupportedOperationException(event + "." + operation + " is not supported by a compile-time "
            + "container: the beans of this container were compiled before it started, and what the operation "
            + "would change is decided while they compile. A build compatible extension can do it there");
    }

    @Override
    public String toString() {
        return name;
    }
}
