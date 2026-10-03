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

import io.micronaut.context.BeanContext;
import io.micronaut.core.annotation.Internal;
import org.jspecify.annotations.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What the processor recorded of the qualifier and interceptor binding types an application was compiled with:
 * the members a type declares, which of them take no part in resolution, whether the type may be repeated and
 * whether it is retained at runtime.
 *
 * <p>These are questions about an annotation class, and the container answers them from the record rather than
 * from the class. A type the application was not compiled with has no record, and is asked of the class itself
 * by the module that reads classes.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class BindingTypes {

    private static final String LOCATION = "META-INF/micronaut-cdi/bindings/";
    private static final Map<String, BindingType> RECORDS = new ConcurrentHashMap<>();
    /**
     * The names each class loader has no record of. A lookup by an annotation the application was not compiled
     * with is made again and again - each selection by it, each resolution it takes part in - and reads the
     * class path only the first time. Weakly keyed, so that a class loader that is done with is let go.
     */
    private static final Map<ClassLoader, Set<String>> MISSING =
        java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    private BindingTypes() {
    }

    /**
     * The record of the annotation type of the given name.
     *
     * @param name The annotation type name
     * @return The record, or {@code null} where the application was not compiled with the type as a qualifier
     * or a binding
     */
    public static @Nullable BindingType of(String name) {
        BindingType known = RECORDS.get(name);
        if (known != null) {
            return known;
        }
        BeanContext context = CdiRunning.currentContext();
        ClassLoader loader = context != null && context.getClassLoader() != null
            ? context.getClassLoader() : BindingTypes.class.getClassLoader();
        BindingType read = read(loader, name);
        if (read == null && loader != BindingTypes.class.getClassLoader()) {
            read = read(BindingTypes.class.getClassLoader(), name);
        }
        if (read != null) {
            RECORDS.put(name, read);
        }
        return read;
    }

    private static @Nullable BindingType read(@Nullable ClassLoader loader, String name) {
        if (loader == null) {
            return null;
        }
        Set<String> missing = MISSING.computeIfAbsent(loader, absent -> ConcurrentHashMap.newKeySet());
        if (missing.contains(name)) {
            return null;
        }
        try (InputStream in = loader.getResourceAsStream(LOCATION + name)) {
            if (in == null) {
                missing.add(name);
                return null;
            }
            boolean qualifier = false;
            boolean binding = false;
            boolean repeatable = false;
            boolean runtime = true;
            Set<String> members = Set.of();
            Set<String> nonbinding = Set.of();
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                int separator = line.indexOf('=');
                if (separator < 0) {
                    continue;
                }
                String value = line.substring(separator + 1).trim();
                switch (line.substring(0, separator)) {
                    case "qualifier" -> qualifier = "true".equals(value);
                    case "binding" -> binding = "true".equals(value);
                    case "repeatable" -> repeatable = "true".equals(value);
                    case "runtime" -> runtime = "true".equals(value);
                    case "members" -> members = names(value);
                    case "nonbinding" -> nonbinding = names(value);
                    default -> {
                    }
                }
            }
            return new BindingType(qualifier, binding, members, nonbinding, repeatable, runtime);
        } catch (IOException e) {
            return null;
        }
    }

    private static Set<String> names(String value) {
        Set<String> names = new LinkedHashSet<>();
        int start = 0;
        while (start < value.length()) {
            int end = value.indexOf(',', start);
            if (end < 0) {
                end = value.length();
            }
            if (end > start) {
                names.add(value.substring(start, end));
            }
            start = end + 1;
        }
        return names;
    }

    /**
     * What was recorded of a binding type.
     *
     * @param qualifier  Whether the type is a qualifier
     * @param binding    Whether the type is an interceptor binding
     * @param members    The members it declares
     * @param nonbinding The members that take no part in resolution
     * @param repeatable Whether it may be given more than once
     * @param runtime    Whether it is retained at runtime
     */
    public record BindingType(boolean qualifier, boolean binding, Set<String> members, Set<String> nonbinding,
                              boolean repeatable, boolean runtime) {

        /**
         * Whether an annotation of the type has no member resolution compares: it is told from another of
         * its type by nothing, and need not be read.
         *
         * @return Whether the type has no binding member
         */
        public boolean isMarker() {
            return nonbinding.containsAll(members);
        }
    }
}
