package io.micronaut.cdi.test;

import io.micronaut.context.ApplicationContext;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.TransientReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * A producer method parameter annotated TransientReference is destroyed when the producer has run
 * (section 3.3.6), reaching the disposer of what produced it.
 */
class TransientReferenceProducerTest {

    static int helperDisposed;

    @Test
    void transientProducerParameterIsDestroyed() {
        helperDisposed = 0;
        try (ApplicationContext context = ApplicationContext.run()) {
            Produced produced = context.getBean(Produced.class);
            assertNotNull(produced);
            assertEquals(1, helperDisposed, "the transient parameter is destroyed when the producer returns");
        }
    }

    public static class Produced {
    }

    public static class Helper {
        void ping() {
        }
    }

    @Dependent
    public static class TheProducer {

        @Produces
        public Helper helper() {
            return new Helper();
        }

        @Produces
        public Produced produce(@TransientReference Helper helper) {
            helper.ping();
            return new Produced();
        }

        public void dispose(@Disposes Helper helper) {
            helperDisposed++;
        }
    }
}
