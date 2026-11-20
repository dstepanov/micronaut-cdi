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
package io.micronaut.cdi.fuzz;

import java.nio.file.Path;
import java.util.Locale;

/**
 * The settings of a run, from the system properties the build sets.
 */
final class FuzzConfig {

    private FuzzConfig() {
    }

    static String processorPath() {
        return required("fuzz.processorPath");
    }

    static String weldPath() {
        return required("fuzz.weldPath");
    }

    static Path reportDir() {
        return Path.of(System.getProperty("fuzz.reportDir", "build/fuzz"));
    }

    static long seed() {
        return Long.parseLong(System.getProperty("fuzz.seed", "1"));
    }

    static int count() {
        return Integer.parseInt(System.getProperty("fuzz.count", "100"));
    }

    static boolean shrink() {
        return Boolean.parseBoolean(System.getProperty("fuzz.shrink", "true"));
    }

    static int shrinkBudget() {
        return Integer.parseInt(System.getProperty("fuzz.shrinkBudget", "150"));
    }

    static ScenarioGenerator.Size size() {
        return ScenarioGenerator.Size.valueOf(System.getProperty("fuzz.size", "medium").toUpperCase(Locale.ROOT));
    }

    /**
     * The seed to run alone, or {@code null} for a campaign.
     */
    static Long replay() {
        String value = System.getProperty("fuzz.replay");
        return value == null || value.isBlank() ? null : Long.valueOf(value);
    }

    /**
     * Whether the sources and traces of every scenario are kept, rather than only of those that diverge.
     */
    static boolean keepAll() {
        return Boolean.parseBoolean(System.getProperty("fuzz.keepAll", "false"));
    }

    private static String required(String name) {
        String value = System.getProperty(name);
        if (value == null) {
            throw new IllegalStateException("The system property " + name + " is not set: run through Gradle");
        }
        return value;
    }
}
