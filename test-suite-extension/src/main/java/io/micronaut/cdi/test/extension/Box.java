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

import java.util.function.Supplier;

/**
 * A generic implementation a synthetic bean exposes only as {@code Supplier<String>}: what the bean is a bean of
 * is what the extension declared, the type argument included, not what the class declares.
 *
 * @param <T> The type of what the box holds
 */
public final class Box<T> implements Supplier<T> {

    private final T content;

    /**
     * A box holding the given content.
     *
     * @param content The content
     */
    public Box(T content) {
        this.content = content;
    }

    @Override
    public T get() {
        return content;
    }
}
