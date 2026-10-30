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
package io.micronaut.cdi.test.se;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.se.SeContainer;
import jakarta.enterprise.inject.se.SeContainerInitializer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The synthetic archive of an SE bootstrap that turned discovery off holds the beans the program selected, whatever
 * package they are in: a bean of the application under {@code io.micronaut} is not the container's infrastructure.
 */
class SeArchiveNamespaceTest {

    /**
     * Selected into the archive.
     */
    @ApplicationScoped
    public static class Chosen {
    }

    /**
     * Not selected, though in a package under {@code io.micronaut}.
     */
    @ApplicationScoped
    public static class Unchosen {
    }

    @Test
    void anUnselectedBeanUnderIoMicronautIsNotInTheArchive() {
        try (SeContainer container = SeContainerInitializer.newInstance().disableDiscovery()
            .addBeanClasses(Chosen.class).initialize()) {
            assertTrue(container.select(Chosen.class).isResolvable());
            assertTrue(container.select(Unchosen.class).isUnsatisfied(),
                "a bean of the application the program did not select is not a bean of the archive");
        }
    }
}
