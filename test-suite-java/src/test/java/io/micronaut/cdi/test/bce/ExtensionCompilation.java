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
package io.micronaut.cdi.test.bce;

import io.micronaut.annotation.processing.test.JavaParser;
import io.micronaut.cdi.processor.extension.BuildCompatibleExtensionVisitor;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A compilation that runs the given build compatible extensions, and only those, named to the processor through
 * its compiler options the way a build names them: the extension is instantiated by the compilation, so what a
 * test wants to know of it the extension keeps where the test can read it.
 */
final class ExtensionCompilation extends JavaParser {

    private static final List<String> OPTIONS = List.of(BuildCompatibleExtensionVisitor.EXTENSIONS_OPTION,
        BuildCompatibleExtensionVisitor.EXTENSIONS_ONLY_OPTION);

    private final List<String> extensions;
    /**
     * The extension options as the system properties held them before the compilation, {@code null} for an absent
     * one.
     */
    private final Map<String, String> incoming = new HashMap<>(2);

    private ExtensionCompilation(List<String> extensions) {
        this.extensions = extensions;
        for (String option : OPTIONS) {
            incoming.put(option, System.getProperty(option));
        }
    }

    @SafeVarargs
    static JavaParser of(Class<? extends BuildCompatibleExtension>... extensions) {
        List<String> names = new ArrayList<>(extensions.length);
        for (Class<? extends BuildCompatibleExtension> extension : extensions) {
            names.add(extension.getName());
        }
        return new ExtensionCompilation(names);
    }

    /**
     * Closes the compilation and restores its options in the system properties as they were before it, where
     * Micronaut's processor copies every {@code micronaut.*} option as it initializes, reads them back as a
     * compilation's options, and restores what was there as its processing is over: a compilation that failed before
     * then would leave the extensions to have their say in every compilation after this one in the process.
     */
    @Override
    public void close() {
        try {
            super.close();
        } finally {
            incoming.forEach((key, value) -> {
                if (value == null) {
                    System.clearProperty(key);
                } else {
                    System.setProperty(key, value);
                }
            });
        }
    }

    @Override
    protected Set<String> getCompilerOptions() {
        Set<String> options = new LinkedHashSet<>(super.getCompilerOptions());
        options.add("-A" + BuildCompatibleExtensionVisitor.EXTENSIONS_OPTION + "=" + String.join(",", extensions));
        options.add("-A" + BuildCompatibleExtensionVisitor.EXTENSIONS_ONLY_OPTION + "=true");
        return options;
    }
}
