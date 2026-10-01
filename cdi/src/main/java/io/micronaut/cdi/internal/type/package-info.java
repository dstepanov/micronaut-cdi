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
/**
 * The one place a {@code java.lang.reflect.Type} of the specification's API is read and made: what the container
 * is handed is turned into an {@link io.micronaut.core.type.Argument} here, and what it hands out is made from one.
 */
@Internal
@NullMarked
package io.micronaut.cdi.internal.type;

import io.micronaut.core.annotation.Internal;
import org.jspecify.annotations.NullMarked;
