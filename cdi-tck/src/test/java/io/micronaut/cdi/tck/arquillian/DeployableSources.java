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

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;

/**
 * The scenario sources a deployment is compiled from.
 *
 * <p>The build unpacks the whole scenario tree of the kit — the deployments it expects a container to reject
 * included — and tells the suite where it is and which patterns name the rejected ones. An archive that names a
 * class of those patterns is compiled from here as it deploys, because deployment is compilation in this
 * container: what the compiler rejects is what a container reports as it refuses a deployment.</p>
 */
final class DeployableSources {

    private static final String SOURCES_PROPERTY = "io.micronaut.cdi.tck.deployableSources";
    private static final String BROKEN_PROPERTY = "io.micronaut.cdi.tck.brokenScenarios";

    private final Path root;
    private final List<PathMatcher> broken;

    private DeployableSources(Path root, List<PathMatcher> broken) {
        this.root = root;
        this.broken = broken;
    }

    /**
     * The sources the build unpacked, read from the properties the suite task set.
     *
     * @return The sources
     */
    static DeployableSources configured() {
        String sources = System.getProperty(SOURCES_PROPERTY);
        if (sources == null) {
            throw new IllegalStateException("The suite was started without " + SOURCES_PROPERTY
                + ", which the build's tckSuite task sets");
        }
        List<PathMatcher> broken = new ArrayList<>();
        for (String pattern : System.getProperty(BROKEN_PROPERTY, "").split(",")) {
            if (!pattern.isBlank()) {
                broken.add(FileSystems.getDefault().getPathMatcher("glob:" + pattern.trim()));
            }
        }
        return new DeployableSources(Path.of(sources), broken);
    }

    /**
     * Whether the class of the given name belongs to a deployment the kit expects a container to reject, and so
     * to an archive that is compiled as it deploys.
     *
     * @param outerClassName The name of an archive's class
     * @return Whether it is one of the rejected deployments' classes
     */
    boolean isBroken(String outerClassName) {
        Path relative = relativePathOf(outerClassName);
        for (PathMatcher matcher : broken) {
            if (matcher.matches(relative)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The source of the class of the given name, when the kit ships one.
     *
     * @param outerClassName The name of an archive's class
     * @return The source text, or {@code null} when the class is not a scenario of the kit
     */
    @Nullable
    String sourceOf(String outerClassName) {
        Path source = root.resolve(relativePathOf(outerClassName));
        if (!Files.isRegularFile(source)) {
            return null;
        }
        String text;
        try {
            text = Files.readString(source);
        } catch (IOException e) {
            throw new UncheckedIOException("The source of " + outerClassName + " could not be read", e);
        }
        // the same rule the build applies to the shared compilation: a source that reaches for the kit's own
        // machinery — the audit annotations are not even published — cannot be compiled here, and its
        // compiled copy is on the classpath already
        if (text.contains("org.jboss.arquillian") || text.contains("org.jboss.shrinkwrap")
            || text.contains("org.jboss.cdi.tck.shrinkwrap")
            || text.contains("org.jboss.test.audit") || text.contains("org.jboss.cdi.tck.AbstractTest")
            || text.contains("jakarta.enterprise.inject.spi.Extension")) {
            return null;
        }
        return text;
    }

    /**
     * Whether the class of the given name is a test class whose nested members are beans of the deployment:
     * the shared compilation filtered its source out — a test class reaches for the kit's machinery — so the
     * definitions of those nested beans exist only if the archive is compiled as it deploys.
     *
     * @param outerClassName The name of an archive's class
     * @return Whether the archive needs its own compilation for this class's nested beans
     */
    boolean declaresFilteredNestedBeans(String outerClassName) {
        String raw = rawSourceOf(outerClassName);
        if (raw == null || sourceOf(outerClassName) != null) {
            // absent, or compiled with the module already
            return false;
        }
        return raw.contains("static class")
            && (raw.contains("@Observes") || raw.contains("@Dependent") || raw.contains("@ApplicationScoped")
                || raw.contains("@RequestScoped") || raw.contains("@Produces"));
    }

    /**
     * The source of the class of the given name as the kit ships it, with none of the filtering
     * {@link #sourceOf} applies: what compiling a test class itself takes.
     *
     * @param outerClassName The name of an archive's class
     * @return The source text, or {@code null} when the kit ships none
     */
    @Nullable
    String rawSourceOf(String outerClassName) {
        Path source = root.resolve(relativePathOf(outerClassName));
        if (!Files.isRegularFile(source)) {
            return null;
        }
        try {
            return Files.readString(source);
        } catch (IOException e) {
            throw new UncheckedIOException("The source of " + outerClassName + " could not be read", e);
        }
    }

    private static Path relativePathOf(String outerClassName) {
        return Path.of(outerClassName.replace('.', '/') + ".java");
    }
}
