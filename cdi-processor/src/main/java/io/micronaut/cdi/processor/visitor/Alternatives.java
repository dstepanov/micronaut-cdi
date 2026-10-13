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

import io.micronaut.cdi.internal.metadata.CdiSelectableAlternative;
import io.micronaut.cdi.processor.Cdi;
import io.micronaut.context.annotation.Primary;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.AnnotationClassValue;
import io.micronaut.inject.ast.Element;

import java.util.List;

/**
 * How an alternative is read into Micronaut's resolution: the one translation a bean class and a producer are
 * given (section 2.7).
 *
 * <p>A selected alternative is preferred over what it is an alternative to, and over another alternative of a lower
 * priority, which Micronaut says with the order of a candidate: it prefers the lowest, the specification the highest
 * priority, so the order is the priority negated - written over the order Micronaut read from the priority itself.
 * One no priority selected is a bean only where the SE bootstrap selects it as the container is built, which a
 * condition of the container decides by the names it is recorded with.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
final class Alternatives {

    private static final String ORDER = "io.micronaut.core.annotation.Order";

    private Alternatives() {
    }

    /**
     * Selects an alternative by its priority.
     *
     * @param element  The bean class or the producer
     * @param priority The priority that selects it
     * @param primary  Whether it is made primary as well, so that an injection point Micronaut resolves prefers it
     */
    static void select(Element element, int priority, boolean primary) {
        if (primary) {
            element.annotate(Primary.class);
        }
        int order = -priority;
        element.removeAnnotation(ORDER);
        element.annotate(ORDER, builder -> builder.value(order));
    }

    /**
     * Makes an alternative no priority selected a bean on the condition that the SE bootstrap selects it, by the
     * class it belongs to or by an alternative stereotype it carries.
     *
     * @param element   The bean class or the producer
     * @param className The class the bootstrap names to select it
     * @param producer  Whether it is a producer, selected with the class that declares it
     */
    static void leaveUnselected(Element element, String className, boolean producer) {
        List<String> stereotypes = element.getAnnotationMetadata()
            .getAnnotationNamesByStereotype(Cdi.ALTERNATIVE).stream()
            .filter(name -> !name.equals(Cdi.ALTERNATIVE))
            .toList();
        element.annotate(Requires.class, builder -> builder
            .member("condition", new AnnotationClassValue<>(
                "io.micronaut.cdi.internal.runtime.UnselectedAlternative")));
        element.annotate(CdiSelectableAlternative.class, builder -> {
            builder.value(className);
            if (producer) {
                builder.member("producer", true);
            }
            if (!stereotypes.isEmpty()) {
                builder.member("stereotypes", stereotypes.toArray(new String[0]));
            }
        });
    }
}
