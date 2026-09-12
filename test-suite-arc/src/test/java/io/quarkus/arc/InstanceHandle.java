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

/**
 * A reference to a bean, and the means to destroy it: ArC's name for
 * {@link jakarta.enterprise.inject.Instance.Handle}.
 *
 * <p>The one thing ArC's handle does that the specification's does not is answer for a lookup that resolved to
 * nothing: {@code instance(Missing.class)} hands back a handle rather than throwing, and the test asks
 * {@link #isAvailable()}. The specification asks {@code Instance.isResolvable()} of the lookup instead, so the
 * handle here carries the answer.</p>
 *
 * @param <T> The bean type
 */
public interface InstanceHandle<T> extends jakarta.enterprise.inject.Instance.Handle<T> {

    /**
     * @return Whether a bean was resolved at all
     */
    boolean isAvailable();

    /**
     * @return The bean this is a handle on
     */
    @Override
    InjectableBean<T> getBean();
}
