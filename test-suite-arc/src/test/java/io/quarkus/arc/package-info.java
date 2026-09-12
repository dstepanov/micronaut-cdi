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
 * The part of ArC's own API the tests fetched into this module are written against.
 *
 * <p>The package is Quarkus's, and so are the names in it: a test of ArC looks a bean up through
 * {@code Arc.container()} rather than through the specification's {@code CDI.current()}, and reads a bean back
 * through ArC's own {@code InjectableBean} rather than through {@code Bean}. The sources are fetched unmodified,
 * so the only way to compile them is to answer for that API; what is here is the smallest set of types that
 * does, each backed by this implementation's own runtime. Nothing of ArC is vendored - none of this is ArC's
 * code, only its shape.</p>
 *
 * <p>Where ArC's API is the specification's with another name, the name is forwarded: an
 * {@code InstanceHandle} is a {@code jakarta.enterprise.inject.Instance.Handle}, an {@code InjectableBean} is a
 * {@code Bean}, and {@code ArcContainer.select} is {@code Instance.select}. Where it is not - a bean's ArC
 * identifier, the interceptor bindings ArC puts in an invocation's context data - the test that depends on it is
 * not on the list, because there would be nothing of this implementation behind it.</p>
 */
package io.quarkus.arc;
