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
package io.micronaut.cdi.test.extension;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * A request scoped synthetic bean of a proxyable class: each request has its own shift, with a number of its own.
 */
public class Shift {

    private static final AtomicInteger NUMBERS = new AtomicInteger();

    private final int number = NUMBERS.incrementAndGet();

    /**
     * The number of this shift.
     *
     * @return The number
     */
    public int number() {
        return number;
    }
}
