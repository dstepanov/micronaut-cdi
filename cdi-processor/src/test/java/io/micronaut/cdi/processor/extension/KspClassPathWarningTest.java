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
package io.micronaut.cdi.processor.extension;

import io.micronaut.inject.visitor.VisitorContext;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.Discovery;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * KSP reads no resource of the class path of the compilation ({@code VisitorContext.getClasspathResources} is
 * empty there), so an async handler registered on it is not found: a compilation with extensions is told so
 * rather than left to find an invoker it expected to be asynchronous synchronous.
 */
class KspClassPathWarningTest {

    @Test
    void aKotlinCompilationWithExtensionsIsWarnedThatTheClassPathIsNotRead() {
        List<String> warnings = new ArrayList<>();
        new BuildCompatibleExtensionVisitor().start(context(VisitorContext.Language.KOTLIN, warnings));
        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains("async handler"), warnings.get(0));
    }

    @Test
    void aJavaCompilationIsNotWarned() {
        List<String> warnings = new ArrayList<>();
        new BuildCompatibleExtensionVisitor().start(context(VisitorContext.Language.JAVA, warnings));
        assertEquals(List.of(), warnings);
    }

    /**
     * A compilation of the given language with the extension of this test, which records what it is warned of.
     */
    private static VisitorContext context(VisitorContext.Language language, List<String> warnings) {
        Map<String, String> options = Map.of(
            BuildCompatibleExtensionVisitor.EXTENSIONS_OPTION, Discovering.class.getName(),
            BuildCompatibleExtensionVisitor.EXTENSIONS_ONLY_OPTION, "true");
        InvocationHandler handler = (proxy, method, arguments) -> switch (method.getName()) {
            case "getLanguage" -> language;
            case "getOptions" -> options;
            case "getClasspathResources" -> List.of();
            case "getClassElement" -> Optional.empty();
            case "warn" -> {
                warnings.add(String.valueOf(arguments[0]));
                yield null;
            }
            default -> {
                if (method.isDefault()) {
                    yield InvocationHandler.invokeDefault(proxy, method, arguments);
                }
                yield null;
            }
        };
        return (VisitorContext) Proxy.newProxyInstance(VisitorContext.class.getClassLoader(),
            new Class<?>[] {VisitorContext.class}, handler);
    }

    /**
     * An extension, named by the options, with a phase method of its own.
     */
    public static final class Discovering implements BuildCompatibleExtension {
        @Discovery
        public void discover() {
        }
    }
}
