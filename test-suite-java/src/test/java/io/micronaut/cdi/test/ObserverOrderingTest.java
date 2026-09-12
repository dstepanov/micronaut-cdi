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
import jakarta.annotation.Priority;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.spi.ObserverMethod;
import jakarta.inject.Qualifier;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The order observers are notified in, what an inherited observer method observes, and the observer that is only
 * notified if its bean already has an instance.
 *
 * <p>Section 2.8.5 notifies observers in ascending order of the priority their {@code @Observes} declares, with
 * {@code Interceptor.Priority.APPLICATION + 500} the priority of one that declares none. Section 2.8.4 makes an
 * observer method of a superclass an observer method of the bean that inherits it. Section 2.8.1 puts Object among
 * the types of every event, and section 2.8.5 notifies an observer whose qualifiers are a subset of the event's -
 * which an observer that declares none always is.</p>
 */
class ObserverOrderingTest {

    @BeforeEach
    void clear() {
        Sounded.ORDER.clear();
    }

    @Test
    void observersAreNotifiedInAscendingOrderOfPriority() {
        try (ApplicationContext context = ApplicationContext.run()) {
            CdiBeanContainer container = context.getBean(CdiBeanContainer.class);
            Event<Sounded> event = container.getEvent().select(Sounded.class);
            event.fire(new Sounded());
            assertEquals(List.of("first", "second", "unprioritised", "last"), Sounded.ORDER);
        }
    }

    @Test
    void anObserverMethodOfASuperclassObservesForTheBeanThatInheritsIt() {
        try (ApplicationContext context = ApplicationContext.run()) {
            CdiBeanContainer container = context.getBean(CdiBeanContainer.class);
            Set<ObserverMethod<? super Sounded>> observers =
                container.resolveObserverMethods(new Sounded());
            // the inherited one belongs to the subclass that inherited it, not to the superclass
            assertTrue(observers.stream().anyMatch(observer ->
                    observer.getBeanClass().equals(Horn.class)),
                () -> "no observer of Horn among " + observers);
        }
    }

    @Test
    void anObserverOfObjectWithNoQualifierOfItsOwnObservesAQualifiedEvent() {
        try (ApplicationContext context = ApplicationContext.run()) {
            CdiBeanContainer container = context.getBean(CdiBeanContainer.class);
            container.getEvent().select(Sounded.class, new Quietly.Literal()).fire(new Sounded());
            // Object is a type of every event (section 2.8.1), and an observer whose qualifiers are a subset of
            // the event's is notified (section 2.8.5) - which an observer that declares none always is. The
            // observer qualified Quietly is notified here and not by the unqualified event of the test above,
            // because there it is the observer's qualifiers that are not a subset
            assertEquals(List.of("first", "second", "anything", "unprioritised", "last"), Sounded.ORDER);
        }
    }

    /**
     * Tells this test's event apart from every other.
     */
    @Qualifier
    @Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD, ElementType.PARAMETER})
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Quietly {

        /**
         * The literal, for selecting it.
         */
        final class Literal extends jakarta.enterprise.util.AnnotationLiteral<Quietly> implements Quietly {
        }
    }

    /**
     * The payload, which records who was notified.
     */
    public static class Sounded {

        /**
         * Who was notified, in the order they were.
         */
        public static final List<String> ORDER = new CopyOnWriteArrayList<>();
    }

    /**
     * Observes the payload four times over, at three priorities and at none.
     */
    @Singleton
    public static class Watchtower {

        void early(@Observes @Priority(1) Sounded sounded) {
            Sounded.ORDER.add("first");
        }

        void then(@Observes @Priority(2) Sounded sounded) {
            Sounded.ORDER.add("second");
        }

        void whenever(@Observes Sounded sounded) {
            Sounded.ORDER.add("unprioritised");
        }

        void late(@Observes @Priority(jakarta.interceptor.Interceptor.Priority.APPLICATION + 1000)
                  Sounded sounded) {
            Sounded.ORDER.add("last");
        }
    }

    /**
     * Declares an observer method its subclass inherits.
     */
    public abstract static class AbstractHorn {

        void heard(@Observes @Priority(5) Sounded sounded) {
            // recorded by neither test's order: what matters is whose observer method it is
        }
    }

    /**
     * Inherits the observer method, and is the bean the inherited one belongs to.
     */
    @Singleton
    public static class Horn extends AbstractHorn {
    }

    /**
     * Observes Object, which every event has among its types.
     */
    @Singleton
    public static class Everything {

        void heard(@Observes @Priority(3) @Quietly Object anything) {
            Sounded.ORDER.add("anything");
        }
    }
}
