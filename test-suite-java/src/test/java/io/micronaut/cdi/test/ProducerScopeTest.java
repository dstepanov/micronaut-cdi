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
package io.micronaut.cdi.test;

import io.micronaut.cdi.runtime.CdiBeanContainer;
import io.micronaut.context.ApplicationContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.spi.InjectionPoint;
import jakarta.enterprise.util.TypeLiteral;
import jakarta.enterprise.util.AnnotationLiteral;
import jakarta.inject.Inject;
import jakarta.inject.Qualifier;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The scope a producer declares is the scope of what it produces, and not the scope of the class that declares it.
 *
 * <p>Two rules of the specification are read off that scope and were read off the wrong one. Section 3.3.2 allows a
 * producer whose type contains a type variable only where what it produces is dependent, and a producer that writes
 * {@code @Dependent} down says exactly that — which is the one scope it may declare, not a reason to reject it.
 * Section 2.5.2.5 lets a dependent bean be told which injection point it was created for, and a dependent producer
 * of an application scoped class is a dependent bean however its class is scoped.</p>
 *
 * <p>Both rules are enforced while the class compiles, so the fact that this class compiles at all is half of what
 * it asserts; the other half is that what was compiled resolves and carries what it should.</p>
 */
class ProducerScopeTest {

    @Test
    void aProducerOfATypeContainingAVariableMayWriteTheDependentScopeDown() {
        try (ApplicationContext context = ApplicationContext.run()) {
            CdiBeanContainer container = context.getBean(CdiBeanContainer.class);
            List<String> strings = container.createInstance()
                .select(new TypeLiteral<List<String>>() {
                }, new Tickets.Literal())
                .get();
            assertNotNull(strings);
            assertTrue(strings.isEmpty());
        }
    }

    @Test
    void aDependentProducerOfAnApplicationScopedClassIsToldItsInjectionPoint() {
        try (ApplicationContext context = ApplicationContext.run()) {
            CdiBeanContainer container = context.getBean(CdiBeanContainer.class);
            Borrower borrower = container.createInstance().select(Borrower.class).get();
            assertEquals("ticket", borrower.ticket.name());
            // the point the ticket was produced for is the field it was injected into
            assertEquals("ticket", borrower.ticket.injectedInto());
        }
    }

    /**
     * What a producer of a type containing a variable produces.
     *
     * @param name         The name the producer gave it
     * @param injectedInto The member of the injection point it was produced for
     */
    record Ticket(String name, String injectedInto) {
    }

    /**
     * Tells this test's list apart from every other list the suite produces.
     */
    @Qualifier
    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
    @java.lang.annotation.Target({java.lang.annotation.ElementType.TYPE,
        java.lang.annotation.ElementType.METHOD, java.lang.annotation.ElementType.FIELD,
        java.lang.annotation.ElementType.PARAMETER})
    public @interface Tickets {

        /**
         * The literal, for selecting it.
         */
        final class Literal extends AnnotationLiteral<Tickets> implements Tickets {
        }
    }

    /**
     * Declares its producers in a singleton, so that the scope of the class is not the scope of what it produces.
     */
    @Singleton
    public static class TicketOffice {

        /**
         * A producer whose type contains a variable, saying the dependent scope it would have had anyway.
         *
         * @param <T> The element type, which only whoever asks for the list knows
         * @return An empty list of it
         */
        @Produces
        @Dependent
        @Tickets
        <T extends Comparable<T>> List<T> produceList() {
            return new ArrayList<>();
        }
    }

    /**
     * An application scoped class whose dependent producer asks where it is producing for.
     */
    @ApplicationScoped
    public static class TicketPrinter {

        /**
         * @param at The injection point the ticket is produced for
         * @return The ticket
         */
        @Produces
        @Dependent
        Ticket produceTicket(InjectionPoint at) {
            return new Ticket("ticket", at.getMember().getName());
        }
    }

    /**
     * Asks for a ticket, so that there is an injection point to be told about.
     */
    @Singleton
    public static class Borrower {

        @Inject
        Ticket ticket;
    }
}
