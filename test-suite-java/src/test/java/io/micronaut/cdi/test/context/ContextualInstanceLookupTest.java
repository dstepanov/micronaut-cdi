package io.micronaut.cdi.test.context;

import io.micronaut.cdi.internal.runtime.CdiBeanContainer;
import io.micronaut.cdi.test.extension.mapscope.MapContext;
import io.micronaut.cdi.test.extension.mapscope.MapScoped;
import io.micronaut.context.ApplicationContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.Reception;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Whether a contextual instance exists is asked of the bean, not of a class it is assignable to, nor of
 * the definitions the context could create; and a context reports the instances it holds.
 */
class ContextualInstanceLookupTest {

    static final List<String> NOTIFIED = Collections.synchronizedList(new ArrayList<>());

    public record Ping() {
    }

    public record Pang() {
    }

    @ApplicationScoped
    public static class Base {

        static final AtomicInteger OWN_INSTANCES = new AtomicInteger();

        public Base() {
            if (getClass() == Base.class) {
                OWN_INSTANCES.incrementAndGet();
            }
        }

        public String name() {
            return "base";
        }

        void on(@Observes(notifyObserver = Reception.IF_EXISTS) Ping ping) {
            NOTIFIED.add(name());
        }
    }

    @ApplicationScoped
    public static class Sub extends Base {

        @Override
        public String name() {
            return "sub";
        }
    }

    @MapScoped
    public static class NeverCreatedScoped {

        static final AtomicInteger INSTANCES = new AtomicInteger();

        public NeverCreatedScoped() {
            if (getClass() == NeverCreatedScoped.class) {
                INSTANCES.incrementAndGet();
            }
        }

        void on(@Observes(notifyObserver = Reception.IF_EXISTS) Pang pang) {
            NOTIFIED.add("scoped");
        }
    }

    @ApplicationScoped
    public static class Counter {
        public int count() {
            return 1;
        }
    }

    @Singleton
    public static class SingletonCounter {
        public int count() {
            return 1;
        }
    }

    /**
     * An instance of Sub exists; none of the bean Base does. Base's conditional observer is not notified, and no
     * instance of Base is created for it.
     */
    @Test
    void anInstanceOfASubclassBeanIsNotAnInstanceOfTheSuperclassBean() {
        try (ApplicationContext context = ApplicationContext.run()) {
            NOTIFIED.clear();
            Base.OWN_INSTANCES.set(0);
            assertEquals("sub", context.getBean(Sub.class).name());

            context.getBean(CdiBeanContainer.class).getEvent().select(Ping.class).fire(new Ping());

            assertEquals(List.of("sub"), NOTIFIED);
            assertEquals(0, Base.OWN_INSTANCES.get(), "an instance of Base was created for its conditional observer");
        }
    }

    @Test
    void aCustomScopedBeanThatWasNeverCreatedDoesNotExist() {
        try (ApplicationContext context = ApplicationContext.run()) {
            NOTIFIED.clear();
            MapContext.STORE.clear();
            NeverCreatedScoped.INSTANCES.set(0);

            context.getBean(CdiBeanContainer.class).getEvent().select(Pang.class).fire(new Pang());

            assertEquals(List.of(), NOTIFIED);
            assertEquals(0, NeverCreatedScoped.INSTANCES.get(), "created for its conditional observer");
        }
    }

    /**
     * Section 10.5: a conditional observer of a bean whose context is not active is not notified, and that is no
     * error.
     */
    @Test
    void aConditionalObserverOfAnInactiveCustomContextIsNotNotified() {
        try (ApplicationContext context = ApplicationContext.run()) {
            NOTIFIED.clear();
            MapContext.STORE.clear();
            MapContext.active = false;
            try {
                assertDoesNotThrow(() ->
                    context.getBean(CdiBeanContainer.class).getEvent().select(Pang.class).fire(new Pang()));
                assertEquals(List.of(), NOTIFIED);
            } finally {
                MapContext.active = true;
            }
        }
    }

    @Test
    void theApplicationContextReportsTheInstanceItHolds() {
        assertTheContextHoldsTheInstance(Counter.class, ApplicationScoped.class);
    }

    @Test
    void theSingletonContextReportsTheInstanceItHolds() {
        assertTheContextHoldsTheInstance(SingletonCounter.class, Singleton.class);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void assertTheContextHoldsTheInstance(Class<?> type, Class<? extends java.lang.annotation.Annotation> scope) {
        try (ApplicationContext context = ApplicationContext.run()) {
            Object reference = context.getBean(type);
            if (reference instanceof Counter counter) {
                counter.count();
            } else if (reference instanceof SingletonCounter counter) {
                counter.count();
            }
            CdiBeanContainer container = context.getBean(CdiBeanContainer.class);
            Bean bean = container.resolve((Set) container.getBeans(type));

            assertNotNull(container.getContext(scope).get(bean),
                type.getSimpleName() + " was created, so its context holds it");
        }
    }
}
