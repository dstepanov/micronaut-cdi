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
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.abort;

/**
 * The kit's language model assertions ran, section by section, as the kit's classes compiled; the extension
 * recorded how each section ended, and this reads the record.
 *
 * <p>A section the model answers from Micronaut's AST must pass. A section that turns on something the AST does
 * not record is pending: it is expected to fail until the record changes, and the day it passes it is reported
 * as a failure here, so that it is taken off the pending list rather than left there.</p>
 */
class LangModelTckTest {

    /**
     * The sections waiting on a change in Micronaut core, each with the finding of {@code
     * MICRONAUT-CORE-FINDINGS.md} that names the change and the assertion the section stops at.
     */
    private static final Map<String, String> PENDING = Map.of(
        "AnnotatedTypes", "the annotation on one dimension of an array (finding #37): Micronaut's model keeps one "
            + "set of annotations for an array type; accepted",
        "RepeatableAnnotations", "a repeatable annotation written once beside a container the source wrote is "
            + "folded into that container, and the two are not told apart (finding #39, mixed case); accepted"
    );

    @TestFactory
    List<DynamicTest> eachSectionOfTheKit() throws IOException {
        Map<String, String> outcomes = outcomes();
        List<DynamicTest> tests = new ArrayList<>();
        outcomes.forEach((section, outcome) -> tests.add(DynamicTest.dynamicTest(section, () -> {
            String pending = PENDING.get(section);
            if (pending == null) {
                assertEquals("passed", outcome, "the section " + section + " fails; the report at "
                    + report() + " has the assertion");
            } else if ("passed".equals(outcome)) {
                fail("the section " + section + " passes now: take it off the pending list (" + pending + ")");
            } else {
                abort("pending on Micronaut core: " + pending);
            }
        })));
        for (String section : PENDING.keySet()) {
            assertTrue(outcomes.containsKey(section), "the pending section " + section + " is not one the "
                + "extension runs");
        }
        return tests;
    }

    @Test
    void theVerifierRanAsTheKitCompiled() throws IOException {
        List<String> lines = Files.readAllLines(report(), StandardCharsets.UTF_8);
        assertEquals(LangModelExtension.VERIFIED + LangModelVerifier.class.getName(), lines.get(0));
        // every section of the verifier's entry point, and the checks around them
        assertEquals(LangModelExtension.SECTIONS.length + 1, outcomes().size());
    }

    @Test
    void theVerifiedClassIsTheOneCompiledHere() {
        String location = LangModelVerifier.class.getProtectionDomain().getCodeSource().getLocation().toString();
        assertTrue(location.contains("cdi-tck-lang-model/build/classes"),
            "the verifier came from " + location + " rather than from the sources compiled here");
    }

    private static Path report() {
        Path report = Path.of(System.getProperty(LangModelExtension.REPORT_PROPERTY));
        assertTrue(Files.exists(report), "the extension did not record that it ran: " + report);
        return report;
    }

    private static Map<String, String> outcomes() throws IOException {
        Map<String, String> outcomes = new LinkedHashMap<>();
        for (String line : Files.readAllLines(report(), StandardCharsets.UTF_8)) {
            if (line.startsWith(LangModelExtension.SECTION)) {
                String[] parts = line.substring(LangModelExtension.SECTION.length()).split(" ");
                outcomes.put(parts[0], parts[1]);
            }
        }
        return outcomes;
    }
}
