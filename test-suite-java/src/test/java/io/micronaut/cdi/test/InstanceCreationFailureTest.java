package io.micronaut.cdi.test;

import io.micronaut.context.ApplicationContext;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * What the constructor of a bean throws comes out of a lookup as it was thrown (section 6.1.1), whichever way the
 * lookup obtains the instance: {@code get()}, iterating it, or a handle.
 */
class InstanceCreationFailureTest {

    @Dependent
    public static class FailingDependent {
        public FailingDependent() {
            throw new IllegalStateException("cannot be constructed");
        }
    }

    @Singleton
    public static class FailingSingleton {
        public FailingSingleton() {
            throw new IllegalStateException("cannot be constructed");
        }
    }

    private static void assertFails(org.junit.jupiter.api.function.Executable lookup) {
        assertEquals("cannot be constructed", assertThrows(IllegalStateException.class, lookup).getMessage());
    }

    @Test
    void aDependentBeanFailsTheSameThroughEveryWayOfObtainingIt() {
        try (ApplicationContext context = ApplicationContext.run()) {
            Instance<FailingDependent> lookup = context.getBean(BeanManager.class).createInstance()
                .select(FailingDependent.class);

            assertFails(lookup::get);
            assertFails(() -> lookup.iterator().next());
            assertFails(() -> lookup.getHandle().get());
            assertFails(() -> lookup.handles().iterator().next().get());
        }
    }

    @Test
    void aBeanOfAScopeFailsTheSameThroughEveryWayOfObtainingIt() {
        try (ApplicationContext context = ApplicationContext.run()) {
            Instance<FailingSingleton> lookup = context.getBean(BeanManager.class).createInstance()
                .select(FailingSingleton.class);

            assertFails(lookup::get);
            assertFails(() -> lookup.getHandle().get());
        }
    }
}
