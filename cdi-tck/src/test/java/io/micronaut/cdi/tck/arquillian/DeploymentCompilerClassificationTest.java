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
package io.micronaut.cdi.tck.arquillian;

import jakarta.enterprise.inject.spi.DefinitionException;
import jakarta.enterprise.inject.spi.DeploymentException;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the harness makes of a deployment that does not compile: an error a processor reported is a definition error,
 * or a deployment problem where the diagnostic says so, while a source javac itself rejects is no finding of the
 * container and fails the test rather than passing one that expects a definition error.
 */
class DeploymentCompilerClassificationTest {

    @Test
    void anErrorTheProcessorReportsIsADefinitionError() {
        RuntimeException failure = assertThrows(RuntimeException.class, () -> DeploymentCompiler.compile(Map.of(
            "tck.classified.Subject", """
                package tck.classified;

                @jakarta.enterprise.context.Dependent
                public class Subject {
                    @jakarta.inject.Inject
                    jakarta.enterprise.inject.spi.Bean<String> bean;
                }
                """)));
        assertInstanceOf(DefinitionException.class, failure);
        assertTrue(failure.getMessage().contains("may only be told about itself"), failure.getMessage());
    }

    @Test
    void aSourceTheCompilerRejectsIsNoDefinitionError() {
        RuntimeException failure = assertThrows(RuntimeException.class, () -> DeploymentCompiler.compile(Map.of(
            "tck.classified.Broken", """
                package tck.classified;

                @jakarta.enterprise.context.Dependent
                public class Broken {
                    NoSuchType field;
                }
                """)));
        assertFalse(failure instanceof DefinitionException || failure instanceof DeploymentException,
            "a source javac rejects is reported as it failed, not as a finding: " + failure);
    }
}
