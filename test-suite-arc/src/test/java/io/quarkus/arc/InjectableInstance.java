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
package io.quarkus.arc;

import jakarta.enterprise.inject.Instance;

/**
 * ArC's name for an {@link Instance}, whose handles are ArC's handles.
 *
 * <p>The specification's {@code Instance} already hands out handles; ArC's interface only narrows their type, so
 * this narrows the two methods that return them and inherits everything else.</p>
 *
 * @param <T> The bean type
 */
public interface InjectableInstance<T> extends Instance<T> {

    @Override
    InstanceHandle<T> getHandle();

    @Override
    Iterable<InstanceHandle<T>> handles();

    @Override
    <U extends T> InjectableInstance<U> select(Class<U> subtype, java.lang.annotation.Annotation... qualifiers);

    @Override
    InjectableInstance<T> select(java.lang.annotation.Annotation... qualifiers);
}
