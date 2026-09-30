package io.micronaut.cdi.test.noreflection;

import io.micronaut.cdi.context.RequestScope;
import io.micronaut.context.ApplicationContext;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.se.SeContainer;
import jakarta.enterprise.inject.se.SeContainerInitializer;
import jakarta.enterprise.inject.spi.BeanContainer;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * What the container destroys, and how a lookup fails, without the reflection module: the dependents looked up
 * through an SE container go as it closes, an instance is disposed of by the disposer of its own producer, the
 * beans of a request are destroyed while the request is active, and what a constructor throws comes out of a
 * handle as it comes out of {@code get()}.
 */
class ContainerCleanupWithoutReflectionTest {

    static final List<String> SEEN = new ArrayList<>();

    @Dependent
    static class NoReflectionLookedUpResource {
        @PreDestroy
        void close() {
            SEEN.add("resource destroyed");
        }
    }

    static class NoReflectionItem {
        final String name;

        NoReflectionItem(String name) {
            this.name = name;
        }
    }

    @Singleton
    static class NoReflectionItems {
        @Produces
        @Singleton
        @Named("noReflectionFirst")
        NoReflectionItem first() {
            return new NoReflectionItem("first");
        }

        @Produces
        @Singleton
        @Named("noReflectionSecond")
        NoReflectionItem second() {
            return new NoReflectionItem("second");
        }

        void disposeFirst(@Disposes @Named("noReflectionFirst") NoReflectionItem item) {
            SEEN.add("first disposer: " + item.name);
        }

        void disposeSecond(@Disposes @Named("noReflectionSecond") NoReflectionItem item) {
            SEEN.add("second disposer: " + item.name);
        }
    }

    @RequestScoped
    static class NoReflectionJournal {
        void note(String entry) {
            SEEN.add(entry);
        }
    }

    @RequestScoped
    static class NoReflectionSession {
        @Inject
        NoReflectionJournal journal;

        void open() {
        }

        @PreDestroy
        void close() {
            journal.note("session closed");
        }
    }

    @Dependent
    static class NoReflectionFailingDependent {
        NoReflectionFailingDependent() {
            throw new IllegalStateException("cannot be constructed");
        }
    }

    @BeforeEach
    void reset() {
        SEEN.clear();
    }

    @Test
    void closingAnSeContainerDestroysTheDependentsLookedUpThroughIt() {
        try (SeContainer container = SeContainerInitializer.newInstance().initialize()) {
            assertNotNull(container.select(NoReflectionLookedUpResource.class).get());
        }
        assertEquals(List.of("resource destroyed"), SEEN);
    }

    @Test
    void anInstanceIsDisposedOfByTheDisposerOfItsOwnProducer() {
        try (ApplicationContext context = ApplicationContext.run()) {
            Instance<NoReflectionItem> items = context.getBean(BeanContainer.class).createInstance()
                .select(NoReflectionItem.class, Any.Literal.INSTANCE);
            List<NoReflectionItem> produced = new ArrayList<>();
            items.forEach(produced::add);
            assertEquals(2, produced.size());
            for (NoReflectionItem item : produced) {
                SEEN.clear();
                items.destroy(item);
                assertEquals(List.of(item.name + " disposer: " + item.name), SEEN);
            }
        }
    }

    @Test
    void theBeansOfARequestAreDestroyedWhileItIsActive() {
        try (ApplicationContext context = ApplicationContext.run()) {
            context.getBean(RequestScope.class).run(() -> context.getBean(NoReflectionSession.class).open());
            assertEquals(List.of("session closed"), SEEN);
        }
    }

    @Test
    void whatAConstructorThrowsComesOutOfAHandleAsItDoesOutOfGet() {
        try (ApplicationContext context = ApplicationContext.run()) {
            Instance<NoReflectionFailingDependent> lookup = context.getBean(BeanContainer.class).createInstance()
                .select(NoReflectionFailingDependent.class);
            assertEquals("cannot be constructed",
                assertThrows(IllegalStateException.class, lookup::get).getMessage());
            assertEquals("cannot be constructed",
                assertThrows(IllegalStateException.class, () -> lookup.getHandle().get()).getMessage());
        }
    }
}
