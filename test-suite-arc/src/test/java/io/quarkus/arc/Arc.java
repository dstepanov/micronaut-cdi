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
 * The entry point a test of ArC reaches the running container through.
 *
 * <p>ArC holds one container per JVM and hands it out statically, which is how its tests find the container the
 * extension started for them. The container here is the application context the extension started, and it is
 * held the same way, so that the fetched tests find it where they look.</p>
 */
public final class Arc {

    private static volatile ArcContainer container;

    private Arc() {
    }

    /**
     * @return The container the test's extension started
     */
    public static ArcContainer container() {
        ArcContainer running = container;
        if (running == null) {
            throw new IllegalStateException("No container is running; the test's ArcTestContainer did not start one");
        }
        return running;
    }

    /**
     * Installs the container a test is to run against.
     *
     * @param started The container, or null once it has stopped
     */
    public static void install(ArcContainer started) {
        container = started;
    }

    /**
     * Stops the running container, which ArC's own extension does after each test.
     */
    public static void shutdown() {
        ArcContainer running = container;
        container = null;
        if (running != null) {
            running.shutdown();
        }
    }
}
