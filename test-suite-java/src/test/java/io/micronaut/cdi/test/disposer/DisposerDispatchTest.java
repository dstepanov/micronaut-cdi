package io.micronaut.cdi.test.disposer;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.BeanRegistration;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A disposer is found by its name alone at runtime, so of two overloads one producer gets the other's;
 * and the arguments of an instance disposer resolve outside the block that destroys them.
 */
class DisposerDispatchTest {

    static final List<String> DISPOSED = Collections.synchronizedList(new ArrayList<>());

    record Foo(String id) {
    }

    record Bar(String id) {
    }

    record Baz(String id) {
    }

    record Qux(String id) {
    }

    /** The control: one disposer of its name. */
    @ApplicationScoped
    static class Single {

        @Produces
        @Dependent
        Qux qux() {
            return new Qux("qux");
        }

        void close(@Disposes Qux qux) {
            DISPOSED.add("close(Qux) " + qux.id());
        }
    }

    @ApplicationScoped
    static class QuxHolder {

        @Inject
        Qux qux;

        String use() {
            return qux.id();
        }
    }

    @ApplicationScoped
    static class Overloads {

        @Produces
        @Dependent
        Foo foo() {
            return new Foo("foo");
        }

        @Produces
        @Dependent
        Bar bar() {
            return new Bar("bar");
        }

        void dispose(@Disposes Foo foo) {
            DISPOSED.add("dispose(Foo) " + foo.id());
        }

        void dispose(@Disposes Bar bar) {
            DISPOSED.add("dispose(Bar) " + bar.id());
        }
    }

    @ApplicationScoped
    static class OverloadHolder {

        @Inject
        Foo foo;

        @Inject
        Bar bar;

        String use() {
            return foo.id() + bar.id();
        }
    }

    /** Created for the disposal, and destroyed when it completes. */
    @Dependent
    static class FirstArgument {

        @PreDestroy
        void destroyed() {
            DISPOSED.add("first argument destroyed");
        }
    }

    /** Fails to be created. */
    @Dependent
    static class FailingArgument {

        FailingArgument() {
            DISPOSED.add("failing argument attempted");
            throw new IllegalStateException("cannot be created");
        }
    }

    @ApplicationScoped
    static class LeakyDisposal {

        @Produces
        @Dependent
        Baz baz() {
            return new Baz("baz");
        }

        void dispose(@Disposes Baz baz, FirstArgument first, FailingArgument failing) {
            DISPOSED.add("disposed " + baz.id());
        }
    }

    @ApplicationScoped
    static class BazHolder {

        @Inject
        Baz baz;

        String use() {
            return baz.id();
        }
    }

    @Test
    void aDisposerWithoutOverloadsRuns() {
        DISPOSED.clear();
        try (ApplicationContext context = ApplicationContext.run()) {
            assertEquals("qux", context.getBean(QuxHolder.class).use());
        }
        assertEquals(List.of("close(Qux) qux"), DISPOSED);
    }

    /**
     * Each produced instance is destroyed on its own, so that the failure of one disposal does not hide the other.
     */
    @Test
    void eachOverloadDisposesOfWhatItsProducerProduced() {
        DISPOSED.clear();
        List<String> failures = new ArrayList<>();
        try (ApplicationContext context = ApplicationContext.run()) {
            for (Class<?> produced : List.of(Foo.class, Bar.class)) {
                BeanRegistration<?> registration = context.getBeanRegistration(context.getBeanDefinition(produced));
                try {
                    context.destroyBean(registration);
                } catch (RuntimeException e) {
                    failures.add(produced.getSimpleName() + ": " + (e.getCause() == null ? e : e.getCause()));
                }
            }
        }
        assertEquals(List.of(), failures);
        assertEquals(List.of("dispose(Bar) bar", "dispose(Foo) foo"), DISPOSED.stream().sorted().toList());
    }

    @Test
    void anArgumentResolvedBeforeAnotherFailedIsDestroyed() {
        DISPOSED.clear();
        RuntimeException closing = null;
        try (ApplicationContext context = ApplicationContext.run()) {
            assertEquals("baz", context.getBean(BazHolder.class).use());
        } catch (RuntimeException e) {
            closing = e;
        }
        assertTrue(DISPOSED.contains("failing argument attempted"), "the second argument was attempted: " + DISPOSED);
        assertTrue(DISPOSED.contains("first argument destroyed"),
            "the dependent argument created for the disposal is destroyed; closing threw " + closing
                + "; recorded " + DISPOSED);
    }
}
