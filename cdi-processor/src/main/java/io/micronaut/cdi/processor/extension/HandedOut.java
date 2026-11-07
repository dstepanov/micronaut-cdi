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

import io.micronaut.cdi.processor.visitor.RecordedTypeValues;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.ElementQuery;
import io.micronaut.inject.ast.FieldElement;
import io.micronaut.inject.ast.MemberElement;
import io.micronaut.inject.ast.MethodElement;
import io.micronaut.inject.ast.ParameterElement;
import io.micronaut.inject.visitor.VisitorContext;
import org.jspecify.annotations.Nullable;

/**
 * The class, and the member of it, a description the registration phase handed an extension was made of, as the
 * compilation has them now.
 *
 * <p>An extension may keep a description and read it in a later phase, and synthesis and validation run once the
 * compilation is finished, after the round that made the description. KSP invalidates the elements of a round as
 * the round ends, so under KSP an element of an earlier round is looked up again: the class by its name and the
 * member by its signature. The other compilers keep the elements of a round valid, and this hands them out as they
 * were.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
final class HandedOut {

    private final ClassElement declaringClass;
    private final @Nullable MemberElement member;
    private final DiscoveredClasses rounds;
    private final int round;
    private final VisitorContext context;
    private final String className;
    private final String canonicalName;
    private final @Nullable String memberKey;

    /**
     * @param declaringClass The class
     * @param member         The member of it, or {@code null} for the class alone
     * @param rounds         What counts the rounds of the compilation
     * @param context        The compilation
     */
    HandedOut(ClassElement declaringClass, @Nullable MemberElement member, DiscoveredClasses rounds,
              VisitorContext context) {
        this.declaringClass = declaringClass;
        this.member = member;
        this.rounds = rounds;
        this.round = rounds.round();
        this.context = context;
        this.className = declaringClass.getName();
        this.canonicalName = declaringClass.getCanonicalName();
        this.memberKey = member == null ? null : keyOf(member);
    }

    /**
     * The same class and member as another, for another description of them.
     *
     * @param member The member of the class, or {@code null} for the class alone
     * @return The class and member
     */
    HandedOut with(@Nullable MemberElement member) {
        return new HandedOut(declaringClass(), member, rounds, context);
    }

    /**
     * @return Whether a member of the class was handed out, rather than the class alone
     */
    boolean isMember() {
        return memberKey != null;
    }

    /**
     * @return The class, as the compilation has it now
     */
    ClassElement declaringClass() {
        if (isOfThisRound()) {
            return declaringClass;
        }
        return context.getClassElement(className)
            .or(() -> context.getClassElement(canonicalName))
            .orElseThrow(() -> new IllegalStateException("The class " + className + " a build compatible extension "
                + "was handed in an earlier round can no longer be found"));
    }

    /**
     * @return The member, as the compilation has it now, or {@code null} for the class alone
     */
    @Nullable MemberElement member() {
        if (member == null || isOfThisRound()) {
            return member;
        }
        ClassElement current = declaringClass();
        ElementQuery<? extends MemberElement> query = member instanceof FieldElement
            ? ElementQuery.ALL_FIELDS : ElementQuery.ALL_METHODS;
        return current.getEnclosedElements(query).stream()
            .filter(candidate -> keyOf(candidate).equals(memberKey))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("The member " + memberKey + " a build compatible extension "
                + "was handed in an earlier round can no longer be found"));
    }

    private boolean isOfThisRound() {
        return rounds.round() == round || context.getLanguage() != VisitorContext.Language.KOTLIN;
    }

    /**
     * What identifies a member among the ones of a class: the class that declares it, its name and, for a method,
     * the erased types of its parameters, so that {@code produce(Foo)} and {@code produce(Foo[])} are two.
     *
     * @param member The member
     * @return The key
     */
    static String keyOf(MemberElement member) {
        StringBuilder key = new StringBuilder(member.getDeclaringType().getName()).append('#')
            .append(member.getName());
        if (member instanceof MethodElement method) {
            key.append('(');
            for (ParameterElement parameter : method.getParameters()) {
                key.append(RecordedTypeValues.erasedName(parameter.getType())).append(',');
            }
            key.append(')');
        }
        return key.toString();
    }
}
