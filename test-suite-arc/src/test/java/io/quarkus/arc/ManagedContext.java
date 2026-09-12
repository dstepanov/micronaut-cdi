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
 * A context a test starts and ends itself: ArC's handle on the request context.
 *
 * <p>A test that asserts over a request scoped bean has to say where the request begins and ends, and there is no
 * request here that it could be part of. The request scope of this implementation takes the same three
 * instructions, so this forwards them.</p>
 */
public interface ManagedContext {

    /**
     * Begins a request.
     */
    void activate();

    /**
     * Ends the request, destroying what was in it.
     */
    void deactivate();

    /**
     * Ends the request, destroying what was in it. ArC distinguishes leaving a request from ending it; this
     * implementation has only the one request per thread, so both end it.
     */
    void terminate();

    /**
     * @return Whether a request is under way
     */
    boolean isActive();
}
