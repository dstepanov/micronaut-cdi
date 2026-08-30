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
import io.micronaut.inject.visitor.TypeElementVisitor;
import io.micronaut.inject.visitor.VisitorContext;

/**
 * Runs the registration phase of section 2.10.3 for each class, after every other visitor: what it describes
 * to the extensions is the bean as everything else — the enhancements included — has left it.
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class RegistrationVisitor implements TypeElementVisitor<Object, Object> {

    @Override
    public VisitorKind getVisitorKind() {
        return VisitorKind.ISOLATING;
    }

    @Override
    public int getOrder() {
        // after every other visitor: the visitors run highest order first, so the lowest runs last
        return HIGHEST_PRECEDENCE;
    }

    @Override
    public void visitClass(ClassElement element, VisitorContext context) {
        BuildCompatibleExtensionVisitor visitor = BuildCompatibleExtensionVisitor.current();
        if (visitor != null) {
            visitor.register(element, new VisitorMessages(context), context);
        }
    }
}
