package io.micronaut.cdi.test;

import io.micronaut.context.ApplicationContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ParameterizedEventLocalTest {

    interface Fooable<T> { }

    static class Foo<F> implements Fooable<F> { }

    static class Bar<B> extends Foo<B> { }

    public abstract static class AbstractObserver<T> {
        private boolean inheritedSeen;

        protected void onInheritedFooable(@Observes Fooable<T> event) {
            inheritedSeen = true;
        }

        public boolean isInheritedSeen() {
            return inheritedSeen;
        }
    }

    @ApplicationScoped
    public static class IntegerListObserver extends AbstractObserver<List<Integer>> {
    }

    @ApplicationScoped
    public static class Observer {
        boolean fooableSeen;
        boolean fooSeen;
        boolean barSeen;

        void onFooable(@Observes Fooable<List<Integer>> event) {
            fooableSeen = true;
        }

        void onFoo(@Observes Foo<List<Integer>> event) {
            fooSeen = true;
        }

        void onBar(@Observes Bar<List<Integer>> event) {
            barSeen = true;
        }

        boolean allSeen() {
            return fooableSeen && fooSeen && barSeen;
        }
    }

    @ApplicationScoped
    public static class Notifier {
        @Inject
        Event<Bar<List<Integer>>> event;

        void fire() {
            event.fire(new Bar<List<Integer>>());
        }
    }

    @Test
    void aParameterizedEventNotifiesItsWholeClosure() {
        try (ApplicationContext context = ApplicationContext.run()) {
            context.getBean(Notifier.class).fire();
            Observer observer = context.getBean(Observer.class);
            assertTrue(observer.allSeen(), "the whole closure observed");
            assertTrue(context.getBean(IntegerListObserver.class).isInheritedSeen(),
                "the inherited observer notified");
        }
    }
}
