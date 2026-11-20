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
package io.micronaut.cdi.internal.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The bean discovery mode of a {@code beans.xml} is the attribute of its root element, read as XML reads it.
 */
class BeanArchivesTest {

    @Test
    void theModeIsTheAttributeOfTheRootElement() {
        assertEquals("none", BeanArchives.discoveryModeOf(
            "<beans xmlns=\"https://jakarta.ee/xml/ns/jakartaee\" bean-discovery-mode=\"none\" version=\"4.0\"/>"));
        assertEquals("annotated", BeanArchives.discoveryModeOf(
            "<?xml version=\"1.0\"?>\n<!-- bean-discovery-mode=\"all\" -->\n<beans bean-discovery-mode='annotated'/>"));
    }

    @Test
    void aDescriptorThatDeclaresNoModeDeclaresNone() {
        assertNull(BeanArchives.discoveryModeOf(""));
        assertNull(BeanArchives.discoveryModeOf("  \n"));
        assertNull(BeanArchives.discoveryModeOf("<beans/>"));
        assertNull(BeanArchives.discoveryModeOf("<beans><scan bean-discovery-mode=\"all\"/></beans>"));
    }

    @Test
    void theModeIsReadAsXmlReadsIt() {
        // a character reference in the value, and the attribute's text outside the root element
        assertEquals("all", BeanArchives.discoveryModeOf("<beans bean-discovery-mode=\"&#97;ll\"/>"));
        assertEquals("annotated", BeanArchives.discoveryModeOf(
            "<?target bean-discovery-mode=\"all\"?><beans bean-discovery-mode=\"annotated\"/>"));
    }
}
