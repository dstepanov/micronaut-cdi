package io.micronaut.cdi.test;

import io.micronaut.context.ApplicationContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * A producer that declares no scope of its own is dependent (CDI 4.1 sections 3.3 and 3.4), whatever scope the
 * class that declares it is in, and a producer of a dependent instance may produce null (section 3.2.2). The normal
 * scope of the declaring class is not the producer's: its null is handed on, not an IllegalProductException.
 */
class NullDependentProductOfANormalScopedClassTest {

    @jakarta.inject.Qualifier
    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
    @interface Absent {
    }

    @jakarta.inject.Qualifier
    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
    @interface Vacant {
    }

    @jakarta.inject.Qualifier
    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
    @interface Missing {
    }

    @ApplicationScoped
    static class NothingProducer {
        @Produces
        @Absent
        static String absent;

        @Produces
        @Vacant
        String vacant;

        @Produces
        @Missing
        Integer missing() {
            return null;
        }
    }

    @Dependent
    static class Consumer {
        @Inject
        @Absent
        Instance<String> absent;

        @Inject
        @Vacant
        Instance<String> vacant;

        @Inject
        @Missing
        Instance<Integer> missing;
    }

    @Test
    void aNullFromAProducerFieldWithNoScopeOfItsOwnIsHandedOn() {
        try (ApplicationContext context = ApplicationContext.run()) {
            Consumer consumer = context.getBean(Consumer.class);
            assertNull(consumer.absent.get());
            assertNull(consumer.vacant.get());
        }
    }

    @Test
    void aNullFromAProducerMethodWithNoScopeOfItsOwnIsHandedOn() {
        try (ApplicationContext context = ApplicationContext.run()) {
            assertNull(context.getBean(Consumer.class).missing.get());
        }
    }
}
