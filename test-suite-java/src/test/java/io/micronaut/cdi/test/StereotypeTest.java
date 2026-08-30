package io.micronaut.cdi.test;

import io.micronaut.context.ApplicationContext;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.enterprise.inject.Stereotype;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.inject.Named;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StereotypeTest {

    @Stereotype
    @ApplicationScoped
    @Named
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    @interface Service {
    }

    @Stereotype
    @RequestScoped
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    @interface PerRequest {
    }

    @Stereotype
    @Alternative
    @ApplicationScoped
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    @interface Mock {
    }

    interface Clock {
        String now();
    }

    @Service
    static class RealClock implements Clock {
        private final java.util.concurrent.atomic.AtomicInteger calls =
            new java.util.concurrent.atomic.AtomicInteger();

        @Override
        public String now() {
            return "real" + calls.incrementAndGet();
        }
    }

    @PerRequest
    static class Basket {
        String id() {
            return "basket";
        }
    }

    interface Ledger {
        String read();
    }

    @ApplicationScoped
    static class RealLedger implements Ledger {
        @Override
        public String read() {
            return "real";
        }
    }

    @Mock
    @Priority(500)
    static class MockLedger implements Ledger {
        @Override
        public String read() {
            return "mock";
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

    @Test
    void aStereotypeCarriesTheScopeItDeclares() {
        // the stereotype declares the application scope, so the bean is in it: the two references reach the one
        // instance the scope holds
        Clock first = context.getBean(RealClock.class);
        Clock second = context.getBean(RealClock.class);
        assertEquals("real1", first.now());
        assertEquals("real2", second.now());
    }

    @Test
    void aStereotypeCarriesTheRequestScopeItDeclares() {
        Basket basket = context.getBean(Basket.class);
        assertTrue(org.junit.jupiter.api.Assertions.assertThrows(
            jakarta.enterprise.context.ContextNotActiveException.class, basket::id).getMessage()
            .contains("request scope"));
    }

    @Test
    void aBeanReportsTheStereotypesItCarries() {
        Bean<?> clock = CDI.current().getBeanContainer().getBeans(Clock.class).iterator().next();
        assertTrue(clock.getStereotypes().contains(Service.class),
            "expected the Service stereotype, got " + clock.getStereotypes());
    }

    @Test
    void aStereotypeCanDeclareThatItsBeansAreAlternatives() {
        // the stereotype declares Alternative, and the bean declares the priority that selects it
        assertEquals("mock", context.getBean(Ledger.class).read());
    }
}
