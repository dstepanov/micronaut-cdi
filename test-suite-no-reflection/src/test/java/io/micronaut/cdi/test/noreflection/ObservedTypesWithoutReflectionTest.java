package io.micronaut.cdi.test.noreflection;

import io.micronaut.cdi.MicronautBeanContainer;
import io.micronaut.cdi.MicronautEvent;
import io.micronaut.cdi.spi.CdiReflection;
import io.micronaut.context.ApplicationContext;
import io.micronaut.core.type.Argument;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.enterprise.inject.spi.ObserverMethod;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * What an observer observes - the type arguments, a wildcard, the variable of a generic superclass as the bean
 * class binds it - is what the processor recorded of the parameter, and is reported and matched with no
 * reflection module on the classpath.
 */
class ObservedTypesWithoutReflectionTest {

    static class Box<T> {

        final T content;

        Box(T content) {
            this.content = content;
        }
    }

    abstract static class Listener<T> {

        final List<Object> heard = new ArrayList<>();

        void on(@Observes Box<T> box) {
            heard.add(box.content);
        }
    }

    @ApplicationScoped
    static class TextListener extends Listener<String> {

        List<Object> heard() {
            return List.copyOf(heard);
        }
    }

    @ApplicationScoped
    static class NumberListener {

        final List<Object> heard = new ArrayList<>();

        void on(@Observes Box<? extends Number> box) {
            heard.add(box.content);
        }

        List<Object> heard() {
            return List.copyOf(heard);
        }
    }

    interface DomainEvent<S> {
    }

    record Order(int number) {
    }

    record Invoice(int number) {
    }

    static class OrderCreated implements DomainEvent<Order> {
    }

    @ApplicationScoped
    static class Ledger {

        final List<String> heard = new ArrayList<>();

        void orders(@Observes DomainEvent<Order> event) {
            heard.add("order");
        }

        void invoices(@Observes DomainEvent<Invoice> event) {
            heard.add("invoice");
        }

        void anything(@Observes DomainEvent<?> event) {
            heard.add("any");
        }

        List<String> heard() {
            return List.copyOf(heard);
        }
    }

    @ApplicationScoped
    static class Sender {

        @Inject
        Event<Box<String>> texts;

        @Inject
        Event<Box<Integer>> numbers;

        void send() {
            texts.fire(new Box<>("hello"));
            numbers.fire(new Box<>(7));
        }
    }

    private ApplicationContext context;

    @BeforeEach
    void start() {
        context = ApplicationContext.run();
    }

    @AfterEach
    void stop() {
        context.close();
    }

    private Type observedBy(Class<?> beanClass) {
        MicronautBeanContainer container = (MicronautBeanContainer) CDI.current().getBeanContainer();
        for (ObserverMethod<?> observer : ((io.micronaut.cdi.internal.runtime.CdiBeanContainer) container)
            .beanContext().getBean(io.micronaut.cdi.internal.runtime.ObserverRegistry.class).observers()) {
            if (observer.getBeanClass() == beanClass) {
                return observer.getObservedType();
            }
        }
        throw new AssertionError("no observer of " + beanClass);
    }

    @Test
    void theObservedTypeOfAnInheritedObserverIsWhatTheBeanClassMakesOfTheVariable() {
        assertFalse(context.findBean(CdiReflection.class).isPresent());
        ParameterizedType observed = assertInstanceOf(ParameterizedType.class, observedBy(TextListener.class));
        assertEquals(Box.class, observed.getRawType());
        assertEquals(String.class, observed.getActualTypeArguments()[0]);
    }

    @Test
    void theObservedTypeKeepsTheWildcardItWasWrittenWith() {
        ParameterizedType observed = assertInstanceOf(ParameterizedType.class, observedBy(NumberListener.class));
        WildcardType wildcard = assertInstanceOf(WildcardType.class, observed.getActualTypeArguments()[0]);
        assertEquals(Number.class, wildcard.getUpperBounds()[0]);
    }

    @Test
    void anEventIsMatchedByTheTypeArgumentsOfWhatItWasFiredAs() {
        context.getBean(Sender.class).send();
        assertEquals(List.of("hello"), context.getBean(TextListener.class).heard());
        assertEquals(List.of(7), context.getBean(NumberListener.class).heard());
    }

    @Test
    void anEventIsMatchedByTheParameterizedSupertypeOfItsClass() {
        // fired as nothing more than an object: what its class implements is what the processor recorded
        ((MicronautBeanContainer) CDI.current().getBeanContainer()).getEvent().fire(new OrderCreated());
        assertEquals(Set.of("order", "any"), Set.copyOf(context.getBean(Ledger.class).heard()));
        assertEquals(2, context.getBean(Ledger.class).heard().size());
    }

    @Test
    void anEventOfAGenericClassFiredWithoutItsTypeArgumentsIsRefused() {
        MicronautEvent<Object> events = ((MicronautBeanContainer) CDI.current().getBeanContainer()).getEvent();
        IllegalArgumentException refused =
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> events.fire(new Box<>("unresolved")));
        org.junit.jupiter.api.Assertions.assertTrue(refused.getMessage().contains("type variable T"),
            refused.getMessage());
        // stated in full, the same object is an event
        events.select(Argument.of(Box.class, String.class)).fire(new Box<>("resolved"));
        assertEquals(List.of("resolved"), context.getBean(TextListener.class).heard());
    }

    @Test
    void anEventSelectedByArgumentIsMatchedByTheArgument() {
        MicronautEvent<Object> events = ((MicronautBeanContainer) CDI.current().getBeanContainer()).getEvent();
        events.select(Argument.of(Box.class, Long.class)).fire(new Box<>(9L));
        assertEquals(Set.of(9L), Set.copyOf(context.getBean(NumberListener.class).heard()));
        assertEquals(List.of(), context.getBean(TextListener.class).heard());
    }
}
