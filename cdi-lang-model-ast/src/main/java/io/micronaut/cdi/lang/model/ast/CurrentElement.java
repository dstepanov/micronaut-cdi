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
package io.micronaut.cdi.lang.model.ast;

import io.micronaut.core.annotation.Internal;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.ConstructorElement;
import io.micronaut.inject.ast.Element;
import io.micronaut.inject.ast.ElementQuery;
import io.micronaut.inject.ast.FieldElement;
import io.micronaut.inject.ast.MemberElement;
import io.micronaut.inject.ast.MethodElement;
import io.micronaut.inject.ast.ParameterElement;
import io.micronaut.inject.ast.PropertyElement;
import io.micronaut.inject.visitor.VisitorContext;

import java.util.List;
import java.util.function.Function;

/**
 * A Micronaut element the language model was made of, as the compilation has it now.
 *
 * <p>An extension may keep what the model handed it and read it in a later phase: synthesis and validation run once
 * the compilation is finished, after the round that made what was kept. KSP invalidates the elements of a round as
 * the round ends, so under KSP an element of an earlier round is looked up again by what identifies it - a class by
 * its name, a member by its owner and signature, a parameter by its method and name. The other compilers keep the
 * elements of a round valid, and this hands them out as they were. Which round the compilation is in is counted by
 * the visitor that runs the extensions ({@link #roundFinished(VisitorContext)}); a compilation that counts none is in
 * its first round throughout.</p>
 *
 * @param <E> The kind of element
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class CurrentElement<E extends Element> {

    private static final String ROUNDS = CurrentElement.class.getName() + ".rounds";

    private final E element;
    private final VisitorContext context;
    private final int round;
    private final String key;
    private final Function<VisitorContext, E> lookup;

    private CurrentElement(E element, VisitorContext context, String key, Function<VisitorContext, E> lookup) {
        this.element = element;
        this.context = context;
        this.round = roundOf(context);
        this.key = key;
        this.lookup = lookup;
    }

    /**
     * A class, looked up again by its name.
     *
     * @param element The class
     * @param context The compilation
     * @return The class
     */
    public static CurrentElement<ClassElement> ofClass(ClassElement element, VisitorContext context) {
        String name = element.getName();
        String canonicalName = element.getCanonicalName();
        return new CurrentElement<>(element, context, name, compilation -> compilation.getClassElement(name)
            .or(() -> compilation.getClassElement(canonicalName))
            .orElseThrow(() -> gone("class " + name)));
    }

    /**
     * A method, a constructor or a field, looked up again among the members of the class it was read as a member
     * of, by the class that declares it and its signature.
     *
     * @param member  The member
     * @param context The compilation
     * @param <M>     The kind of member
     * @return The member
     */
    public static <M extends MemberElement> CurrentElement<M> ofMember(M member, VisitorContext context) {
        CurrentElement<ClassElement> owner = ofClass(member.getOwningType(), context);
        String key = keyOf(member);
        ElementQuery<? extends MemberElement> query = member instanceof ConstructorElement ? ElementQuery.CONSTRUCTORS
            : member instanceof FieldElement ? ElementQuery.ALL_FIELDS.includeHiddenElements()
            : ElementQuery.ALL_METHODS.includeHiddenElements().includeOverriddenMethods();
        return new CurrentElement<>(member, context, key, compilation -> {
            @SuppressWarnings("unchecked")
            M found = (M) owner.get().getEnclosedElements(query).stream()
                .filter(candidate -> keyOf(candidate).equals(key))
                .findFirst()
                .orElseThrow(() -> gone("member " + key));
            return found;
        });
    }

    /**
     * A parameter, looked up again by its name among the parameters of its method.
     *
     * @param method    The method, as the compilation has it now
     * @param parameter The parameter
     * @param context   The compilation
     * @return The parameter
     */
    public static CurrentElement<ParameterElement> ofParameter(CurrentElement<? extends MethodElement> method,
                                                               ParameterElement parameter, VisitorContext context) {
        String name = parameter.getName();
        return new CurrentElement<>(parameter, context, method.key() + "/" + name, compilation -> {
            for (ParameterElement candidate : method.get().getParameters()) {
                if (candidate.getName().equals(name)) {
                    return candidate;
                }
            }
            throw gone("parameter " + name + " of " + method.key());
        });
    }

    /**
     * A property, looked up again by its name among the properties of its class.
     *
     * @param owner    The class, as the compilation has it now
     * @param property The property
     * @param context  The compilation
     * @return The property
     */
    public static CurrentElement<PropertyElement> ofProperty(CurrentElement<ClassElement> owner,
                                                             PropertyElement property, VisitorContext context) {
        String name = property.getName();
        return new CurrentElement<>(property, context, owner.key() + "." + name, compilation -> {
            List<PropertyElement> properties = owner.get().getBeanProperties();
            return properties.stream().filter(candidate -> candidate.getName().equals(name)).findFirst()
                .orElseThrow(() -> gone("property " + name + " of " + owner.key()));
        });
    }

    /**
     * An element the compilation answers the same whatever the round, which is never looked up again.
     *
     * @param element The element
     * @param context The compilation
     * @param <E>     The kind of element
     * @return The element
     */
    public static <E extends Element> CurrentElement<E> asIs(E element, VisitorContext context) {
        return new CurrentElement<>(element, context, element.getName(), compilation -> element);
    }

    /**
     * Notes that a round of the compilation finished, or that the compilation did.
     *
     * @param context The compilation
     */
    public static void roundFinished(VisitorContext context) {
        Rounds rounds = context.get(ROUNDS, Rounds.class).orElse(null);
        if (rounds == null) {
            rounds = new Rounds();
            context.put(ROUNDS, rounds);
        }
        rounds.finished++;
    }

    /**
     * The element, as the compilation has it now.
     *
     * @return The element
     */
    public E get() {
        return isAsHandedOut() ? element : lookup.apply(context);
    }

    /**
     * Whether the element is the one handed out, rather than one looked up again.
     *
     * @return Whether it is of the round the compilation is in, or of a compilation whose elements outlive a round
     */
    public boolean isAsHandedOut() {
        return context.getLanguage() != VisitorContext.Language.KOTLIN || roundOf(context) == round;
    }

    /**
     * What identifies the element whatever the round: equal elements have equal keys.
     *
     * @return The key
     */
    public String key() {
        return key;
    }

    /**
     * What identifies a member among the ones of a class: the class that declares it, its name and, for a method,
     * the types of its parameters, so that {@code produce(Foo)} and {@code produce(Foo[])} are two.
     *
     * @param member The member
     * @return The key
     */
    public static String keyOf(MemberElement member) {
        StringBuilder key = new StringBuilder(member.getDeclaringType().getName()).append('#')
            .append(member instanceof ConstructorElement ? "<init>" : member.getName());
        if (member instanceof MethodElement method) {
            key.append('(');
            for (ParameterElement parameter : method.getParameters()) {
                key.append(parameter.getType().getName()).append(',');
            }
            key.append(')');
        }
        return key.toString();
    }

    private static int roundOf(VisitorContext context) {
        return context.get(ROUNDS, Rounds.class).map(rounds -> rounds.finished).orElse(0);
    }

    private static IllegalStateException gone(String what) {
        return new IllegalStateException("The " + what + " the language model was read from in an earlier round "
            + "can no longer be found");
    }

    /**
     * How many rounds of a compilation finished, kept with the compilation it counts.
     */
    private static final class Rounds {
        private int finished;
    }
}
