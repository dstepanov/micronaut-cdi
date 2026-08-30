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
import jakarta.enterprise.inject.spi.InjectionPoint;
import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * The injection point a dependent bean is being created for, when the resolution path alone cannot say.
 *
 * <p>A bean created for an injection point knows it from the resolution path — the segment above its own is
 * where it is going. A bean obtained through a lookup is created in a resolution of its own, and section
 * 2.5.2.5 says its injection point is the one the {@code Instance} itself was injected into; the lookup leaves
 * that here around the creation, and the metadata bean reads it when the path has nothing to say.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class CurrentInjectionPoint {

    private static final ThreadLocal<Deque<InjectionPoint>> CURRENT = ThreadLocal.withInitial(ArrayDeque::new);

    private CurrentInjectionPoint() {
    }

    public static void enter(InjectionPoint injectionPoint) {
        CURRENT.get().push(injectionPoint);
    }

    public static void leave() {
        Deque<InjectionPoint> stack = CURRENT.get();
        stack.pop();
        if (stack.isEmpty()) {
            CURRENT.remove();
        }
    }

    @Nullable
    static InjectionPoint current() {
        Deque<InjectionPoint> stack = CURRENT.get();
        InjectionPoint current = stack.peek();
        if (current == null) {
            CURRENT.remove();
        }
        return current;
    }
}
