package io.micronaut.cdi.test.graalvm;

import io.micronaut.cdi.test.graalvm.hidden.Tick;
import io.micronaut.context.ApplicationContext;
import io.micronaut.core.type.TypeHierarchies;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The matching of types by their arguments, inside a GraalVM native image, with nothing read from a class: an
 * event is observed by a parameterized supertype of its class, and a bean is resolved by a parameterized type it
 * has, by what the processor recorded while the tests compiled. The records are Micronaut's type hierarchy records
 * of the classes, found through core's service loading, and the bean types a definition records.
 *
 * <p>Each test asserts the same on the JVM, where {@code test} runs it, and in the image, where {@code nativeTest}
 * does. The suite has no module that reads a class: an answer here is the record's.</p>
 */
class NativeImageRecordedTypesTest {

    // (a) a non-bean class with a generic supertype

    interface DomainEvent<E> {
    }

    record Order(int number) {
    }

    record Invoice(int number) {
    }

    static final class InvoicePaid implements DomainEvent<Invoice> {
    }

    static final class OrderPlaced implements DomainEvent<Order> {
    }

    // (b) a bean whose generic closure comes from its record

    interface Repo<E> {
        String kind();
    }

    abstract static class BaseRepo<E> implements Repo<E> {
    }

    @Dependent
    static class StringRepo extends BaseRepo<String> {
        @Override
        public String kind() {
            return "strings";
        }
    }

    // (d) a JDK class named as a type argument

    static class Pile<T> {
    }

    static final class Lines extends Pile<ArrayList<String>> {
    }

    // (e) a produced type

    static final class MapRepo<E> implements Repo<E> {
        @Override
        public String kind() {
            return "produced";
        }
    }

    @Dependent
    static class Producers {
        @Produces
        Repo<Integer> integers() {
            return new MapRepo<>();
        }
    }

    @ApplicationScoped
    static class Listener {
        final List<String> heard = new ArrayList<>();

        void invoices(@Observes DomainEvent<Invoice> event) {
            heard.add("invoice " + event.getClass().getSimpleName());
        }

        void orders(@Observes DomainEvent<Order> event) {
            heard.add("order " + event.getClass().getSimpleName());
        }

        void instants(@Observes Signal<Instant> signal) {
            heard.add("instant " + signal.getClass().getSimpleName());
        }

        void numbers(@Observes Signal<Integer> signal) {
            heard.add("integer " + signal.getClass().getSimpleName());
        }

        void strings(@Observes Collection<String> strings) {
            heard.add("collection " + strings);
        }

        void iterated(@Observes Iterable<String> strings) {
            heard.add("iterable " + strings);
        }

        void integers(@Observes List<Integer> integers) {
            heard.add("integers " + integers);
        }

        List<String> heard() {
            return List.copyOf(heard);
        }
    }

    @Dependent
    static class Client {
        @Inject
        Event<Object> anything;
        @Inject
        Event<DomainEvent<Invoice>> invoices;
        @Inject
        Event<List<String>> lines;
        @Inject
        Instance<Repo<String>> stringRepos;
        @Inject
        Instance<Repo<Integer>> integerRepos;
        @Inject
        Instance<Repo<Long>> longRepos;
    }

    @Test
    void anEventOfANonBeanClassIsObservedByItsGenericSupertype() {
        try (ApplicationContext context = ApplicationContext.run()) {
            Client client = context.getBean(Client.class);
            client.invoices.fire(new InvoicePaid());
            // fired as Object, the class alone says what it is a DomainEvent of
            client.anything.fire(new InvoicePaid());
            client.anything.fire(new OrderPlaced());
            assertEquals(
                List.of("invoice InvoicePaid", "invoice InvoicePaid", "order OrderPlaced"),
                context.getBean(Listener.class).heard());
            assertTrue(TypeHierarchies.find(InvoicePaid.class).isPresent(), "the class of the event is recorded");
            assertFalse(TypeHierarchies.find(DomainEvent.class).isPresent(), "an interface is no class of an event");
        }
    }

    @Test
    void aBeanIsResolvedByTheParameterizedTypeItsRecordGivesIt() {
        try (ApplicationContext context = ApplicationContext.run()) {
            Client client = context.getBean(Client.class);
            assertTrue(client.stringRepos.isResolvable());
            assertEquals("strings", client.stringRepos.get().kind());
            assertSame(StringRepo.class, client.stringRepos.get().getClass());
            assertTrue(client.longRepos.isUnsatisfied(), "no bean is a Repo<Long>");
            assertTrue(TypeHierarchies.find(StringRepo.class).isPresent(), "the bean class is recorded");
        }
    }

    @Test
    void anEventWhoseSupertypeIsPackagePrivateIsObservedByWhatIsAboveIt() {
        try (ApplicationContext context = ApplicationContext.run()) {
            Client client = context.getBean(Client.class);
            client.anything.fire(new Tick());
            assertEquals(List.of("instant Tick"), context.getBean(Listener.class).heard());
            assertTrue(TypeHierarchies.find(Tick.class).isPresent(), "the class is recorded in its own package");
        }
    }

    @Test
    void anEventOfAJdkClassNamedAsATypeArgumentIsObservedByItsSupertypes() {
        try (ApplicationContext context = ApplicationContext.run()) {
            Client client = context.getBean(Client.class);
            ArrayList<String> lines = new ArrayList<>(List.of("one"));
            client.lines.fire(lines);
            assertEquals(List.of("collection [one]", "iterable [one]"), context.getBean(Listener.class).heard());
            assertTrue(TypeHierarchies.find(ArrayList.class).isPresent(), "a JDK class a recorded type names is recorded");
            assertTrue(TypeHierarchies.find(Lines.class).isPresent(), "the class that names it is recorded");
        }
    }

    @Test
    void aProducedTypeIsResolvedByItsArguments() {
        try (ApplicationContext context = ApplicationContext.run()) {
            Client client = context.getBean(Client.class);
            assertTrue(client.integerRepos.isResolvable());
            assertEquals("produced", client.integerRepos.get().kind());
            assertSame(MapRepo.class, client.integerRepos.get().getClass());
            // the producer produces a Repo<Integer>, not a Repo<String>: the string bean is the class alone
            assertSame(StringRepo.class, client.stringRepos.get().getClass());
        }
    }
}
