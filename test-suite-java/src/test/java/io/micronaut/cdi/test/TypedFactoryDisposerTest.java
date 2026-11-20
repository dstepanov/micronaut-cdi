package io.micronaut.cdi.test;

import io.micronaut.context.ApplicationContext;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.Typed;
import jakarta.enterprise.inject.spi.BeanManager;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A disposer declared by a bean that narrowed its bean types with {@code Typed}, so that it is not a bean of its own
 * class, disposes of what its producer made (CDI 4.1 section 3.4.3): the declaring bean is found though a lookup of
 * its class does not find it. And the {@code Typed} of the class narrows the class bean alone, not what its producer
 * produces (section 2.2.2).
 */
class TypedFactoryDisposerTest {

    @Test
    void theDisposerOfABeanTypedAwayFromItsClassDisposes() {
        Factory.DISPOSED.set(0);
        try (ApplicationContext context = ApplicationContext.run()) {
            Instance<Object> lookup = context.getBean(BeanManager.class).createInstance();
            Instance.Handle<Resource> handle = lookup.select(Resource.class).getHandle();
            handle.get();
            handle.destroy();
            assertEquals(1, Factory.DISPOSED.get());
        }
    }

    /**
     * What the factory produces.
     */
    public static final class Resource {
    }

    /**
     * A factory that is a bean only of {@code Object}.
     */
    @Dependent
    @Typed(Object.class)
    public static class Factory {

        static final AtomicInteger DISPOSED = new AtomicInteger();

        @Produces
        @Dependent
        static Resource open() {
            return new Resource();
        }

        static void close(@Disposes Resource resource) {
            DISPOSED.incrementAndGet();
        }
    }
}
