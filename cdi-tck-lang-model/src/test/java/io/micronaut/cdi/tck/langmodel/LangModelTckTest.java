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
package io.micronaut.cdi.tck.langmodel;

import org.jboss.cdi.lang.model.tck.LangModelVerifier;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The kit's language model assertions ran as the kit's classes compiled: a failed one fails the compilation,
 * so what is left to check is that the verifier was run at all, and on the class compiled here rather than the
 * copy in the kit's jar.
 */
class LangModelTckTest {

    @Test
    void theVerifierRanAsTheKitCompiled() throws Exception {
        Path report = Path.of(System.getProperty(LangModelExtension.REPORT_PROPERTY));
        assertTrue(Files.exists(report), "the extension did not record that it ran: " + report);
        assertEquals("verified " + LangModelVerifier.class.getName(),
            Files.readString(report, StandardCharsets.UTF_8));
    }

    @Test
    void theVerifiedClassIsTheOneCompiledHere() {
        String location = LangModelVerifier.class.getProtectionDomain().getCodeSource().getLocation().toString();
        assertTrue(location.contains("cdi-tck-lang-model/build/classes"),
            "the verifier came from " + location + " rather than from the sources compiled here");
    }
}
