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

import io.micronaut.cdi.lang.model.ast.CurrentElement;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.MemberElement;
import io.micronaut.inject.visitor.VisitorContext;
import org.jspecify.annotations.Nullable;

/**
 * The class, and the member of it, a description the registration phase handed an extension was made of, as the
 * compilation has them whenever the description is read ({@link CurrentElement}): an extension may keep the
 * description and read it in synthesis or validation, once the compilation is finished.
 *
 * @author Denis Stepanov
 * @since 1.0
 */
final class HandedOut {

    private final CurrentElement<ClassElement> declaringClass;
    private final @Nullable CurrentElement<MemberElement> member;
    private final VisitorContext context;

    /**
     * @param declaringClass The class
     * @param member         The member of it, or {@code null} for the class alone
     * @param context        The compilation
     */
    HandedOut(ClassElement declaringClass, @Nullable MemberElement member, VisitorContext context) {
        this.declaringClass = CurrentElement.ofClass(declaringClass, context);
        this.member = member == null ? null : CurrentElement.ofMember(member, context);
        this.context = context;
    }

    /**
     * The same class with another member of it, for another description of them.
     *
     * @param member The member of the class, or {@code null} for the class alone
     * @return The class and member
     */
    HandedOut with(@Nullable MemberElement member) {
        return new HandedOut(declaringClass(), member, context);
    }

    /**
     * Whether a member of the class was handed out, rather than the class alone.
     *
     * @return Whether it was
     */
    boolean isMember() {
        return member != null;
    }

    /**
     * The class, as the compilation has it now.
     *
     * @return The class
     */
    ClassElement declaringClass() {
        return declaringClass.get();
    }

    /**
     * The member, as the compilation has it now.
     *
     * @return The member, or {@code null} for the class alone
     */
    @Nullable MemberElement member() {
        return member == null ? null : member.get();
    }
}
