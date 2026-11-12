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
package io.micronaut.cdi.processor.visitor;

import io.micronaut.cdi.internal.metadata.CdiBeanAttributes;
import io.micronaut.cdi.internal.metadata.CdiScope;
import io.micronaut.cdi.processor.Cdi;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.Element;
import io.micronaut.inject.ast.MemberElement;
import io.micronaut.inject.visitor.VisitorContext;

/**
 * The attributes of a bean CDI 5.0 added - reserve (section 2.8), eager initialization (2.9) and automatic closing
 * (2.10) - read from a class bean or a producer, checked, and recorded as {@link CdiBeanAttributes} for the
 * container.
 *
 * <p>Each is declared on the bean class, the producer method or the producer field, or by a stereotype declared
 * there; none of them is inherited by a producer from the class that declares it, which is why a producer's are
 * read from what the producer itself declares.</p>
 *
 * @param reserve   Whether the bean is a reserve
 * @param eager     Whether the bean is eagerly initialized
 * @param autoClose Whether the bean is auto-closeable
 * @author Denis Stepanov
 * @since 1.0
 */
record BeanAttributes(boolean reserve, boolean eager, boolean autoClose) {

    /**
     * Whether the bean has any of the attributes.
     *
     * @return Whether there is anything to record
     */
    boolean any() {
        return reserve || eager || autoClose;
    }

    /**
     * The attributes of a class bean: written on the class or carried by a stereotype it declares. None of the three
     * annotations is inherited, so a superclass's do not count.
     *
     * @param element The class
     * @return The attributes
     */
    static BeanAttributes ofClass(ClassElement element) {
        return new BeanAttributes(element.hasStereotype(Cdi.RESERVE), element.hasStereotype(Cdi.EAGER),
            element.hasStereotype(Cdi.AUTO_CLOSE));
    }

    /**
     * The attributes of a producer: written on the producer or carried by a stereotype it declares, and not the
     * ones of the class that declares it, which its annotation metadata carries behind its own.
     *
     * @param producer The producer method or field
     * @return The attributes
     */
    static BeanAttributes ofProducer(MemberElement producer) {
        return new BeanAttributes(declares(producer, Cdi.RESERVE), declares(producer, Cdi.EAGER),
            declares(producer, Cdi.AUTO_CLOSE));
    }

    private static boolean declares(MemberElement producer, String annotation) {
        return producer.hasDeclaredAnnotation(annotation) || producer.hasDeclaredStereotype(annotation);
    }

    /**
     * Records the attributes, every member written so that a producer's record shadows the one of its class.
     *
     * @param element The class or the producer
     */
    void recordOn(Element element) {
        element.annotate(CdiBeanAttributes.class, builder -> builder
            .member("reserve", reserve)
            .member("eager", eager)
            .member("autoClose", autoClose));
    }

    /**
     * Checks what the specification makes a definition error of: a reserve that is also an alternative (section
     * 2.8.1), and an eagerly initialized bean of another scope than the application scope - a normal scope or the
     * dependent pseudo-scope (section 2.9). An eager bean of another pseudo-scope is non-portable, and allowed.
     *
     * @param element     The class or the producer, which the failure is reported on
     * @param alternative Whether the bean is an alternative
     * @param scope       The scope the bean was resolved to, the dependent pseudo-scope where it declares none
     * @param normal      Whether that scope is a normal one
     * @param context     The compilation
     * @return Whether the attributes are well-formed; a failure has been reported where they are not
     */
    boolean check(Element element, boolean alternative, String scope, boolean normal, VisitorContext context) {
        if (reserve && alternative) {
            context.fail("A bean may not be both a reserve and an alternative (section 2.8.1)", element);
            return false;
        }
        if (eager && !Cdi.APPLICATION_SCOPED.equals(scope) && (normal || Cdi.DEPENDENT.equals(scope))) {
            context.fail("Only an @ApplicationScoped bean may be eagerly initialized; this one is "
                + (normal ? "in the normal scope " + scope : "@Dependent") + " (section 2.9)", element);
            return false;
        }
        return true;
    }

    /**
     * Checks the stereotypes the element declares that make a bean eagerly initialized (section 2.11.1.7): such a
     * stereotype may declare no scope but the application scope, since only an application scoped bean may be eager.
     *
     * @param element The class or the producer
     * @param context The compilation
     * @return Whether they are well-formed; a failure has been reported where they are not
     */
    static boolean checkEagerStereotypes(Element element, VisitorContext context) {
        for (String name : element.getAnnotationMetadata().getAnnotationNamesByStereotype(Cdi.STEREOTYPE)) {
            ClassElement stereotype = context.getClassElement(name).orElse(null);
            if (stereotype == null || !stereotype.hasDeclaredAnnotation(Cdi.EAGER)) {
                continue;
            }
            for (String declared : stereotype.getDeclaredAnnotationNames()) {
                boolean otherNormalScope = !Cdi.APPLICATION_SCOPED.equals(declared) && context.getClassElement(declared)
                    .map(annotation -> annotation.hasDeclaredAnnotation(Cdi.NORMAL_SCOPE)).orElse(false);
                if (Cdi.DEPENDENT.equals(declared) || otherNormalScope) {
                    context.fail("The stereotype " + name + " makes its beans eagerly initialized and declares the "
                        + "scope " + declared + ", which may not be eagerly initialized (section 2.9)", element);
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * The scope recorded on the element, read as {@link #check} takes it: the dependent pseudo-scope where none is.
     *
     * @param element The class or the producer
     * @return The name of the scope annotation
     */
    static String scopeOf(Element element) {
        return element.getAnnotationMetadata().stringValue(CdiScope.class).orElse(Cdi.DEPENDENT);
    }

    /**
     * Whether the scope recorded on the element is a normal one.
     *
     * @param element The class or the producer
     * @return Whether it is normal
     */
    static boolean isNormalScoped(Element element) {
        return element.getAnnotationMetadata().booleanValue(CdiScope.class, "normal").orElse(false);
    }
}
