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

import io.micronaut.core.annotation.Internal;
import io.micronaut.inject.visitor.VisitorContext;
import org.jspecify.annotations.Nullable;

/**
 * What the visitors of this package keep for the rest of a compilation, kept on the compilation itself: the
 * attributes of its {@link VisitorContext}.
 *
 * <p>Two things are kept: the visitor that started the compilation, whose state the registration visitor and the
 * annotation remapping work with, and the record of each class of the type index, decided as the round finishes
 * and put on the class when it is visited rounds later, by whichever instance of a visitor the compiler hands it
 * to. The phases that follow registration need nothing kept: they run as the compiler finishes a round, and
 * register their beans without a source (see {@link BuildCompatibleExtensionVisitor#finishRound}). The
 * attributes are shared by every
 * visitor the compilation runs and are gone with it, which a static field is not: two compilations in one
 * process, the test classes after the main ones or the deployments of the specification's test kit one after
 * the other, each see their own.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
final class CompilationState {

    private static final String PREFIX = CompilationState.class.getName() + ".";

    private CompilationState() {
    }

    /**
     * Keeps a value for the rest of the compilation.
     *
     * @param context The compilation
     * @param key     What the value is kept under
     * @param value   The value
     */
    static void keep(VisitorContext context, String key, Object value) {
        context.put(PREFIX + key, value);
    }

    /**
     * The value kept under the given key, which stays kept.
     *
     * @param context The compilation
     * @param key     What the value was kept under
     * @param type    The type of the value
     * @param <T>     The type of the value
     * @return The value, or {@code null} where none is kept
     */
    static <T> @Nullable T get(VisitorContext context, String key, Class<T> type) {
        Object value = context.getValue(PREFIX + key);
        return type.isInstance(value) ? type.cast(value) : null;
    }

    /**
     * Takes the value kept under the given key, which is no longer kept.
     *
     * @param context The compilation
     * @param key     What the value was kept under
     * @param type    The type of the value
     * @param <T>     The type of the value
     * @return The value, or {@code null} where none was kept
     */
    static <T> @Nullable T take(VisitorContext context, String key, Class<T> type) {
        T value = get(context, key, type);
        if (value != null) {
            context.remove(PREFIX + key);
        }
        return value;
    }
}
