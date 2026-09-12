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
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.visitor.VisitorContext;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.RetentionPolicy;

/**
 * The annotation interfaces an annotation written on a declaration names, looked up in the compilation.
 *
 * <p>Micronaut records an annotation by name and by the values written for it, and the interface that declares it
 * is not part of that record. The language model asks two things of the interface that the record cannot answer:
 * how long the annotation is retained, since only a runtime retained annotation is part of the model, and the
 * declaration itself, which an extension reads the members and their defaults from. Both are answered by asking
 * the compiler for the class of the name.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
final class ExtensionAnnotationTypes {

    private ExtensionAnnotationTypes() {
    }

    /**
     * Whether an annotation of the given name is retained until runtime, which is the only kind of annotation the
     * language model reports.
     *
     * <p>An annotation whose interface the compilation cannot see is reported, rather than silently dropped: what
     * is written on a declaration is the more reliable of the two, and an unresolvable name is a broken
     * compilation of its own.</p>
     *
     * @param name The annotation name
     * @return Whether it is retained until runtime
     */
    static boolean isRuntimeRetained(String name) {
        VisitorContext context = BuildCompatibleExtensionVisitor.activeVisitorContext();
        // the compiler's own answer, which is RUNTIME for a name it cannot resolve
        return context == null || context.getAnnotationRetentionPolicy(name) == RetentionPolicy.RUNTIME;
    }

    /**
     * The class of an annotation of the given name, as the compilation sees it.
     *
     * @param name The annotation name
     * @return The class, or {@code null} when the compilation cannot see it
     */
    static @Nullable ClassElement declarationOf(String name) {
        VisitorContext context = BuildCompatibleExtensionVisitor.activeVisitorContext();
        return context == null ? null : context.getClassElement(name).orElse(null);
    }
}
