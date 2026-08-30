package io.micronaut.cdi.test;

import io.micronaut.cdi.test.extension.Audited;
import io.micronaut.cdi.test.extension.AutoInject;
import io.micronaut.context.ApplicationContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.CDI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The enhancement phase of a build compatible extension, run while these classes were compiled.
 *
 * <p>The extension is {@code io.micronaut.cdi.test.extension.AuditingExtension}, which is on the annotation
 * processor path of this project. Neither class below says it is a bean, and neither field says it is an
 * injection point: the extension is what made them so, and what is asserted here is the container that resulted.
 */
class BuildCompatibleExtensionTest {

    @ApplicationScoped
    static class Clock {
        String now() {
            return "noon";
        }
    }

    // no scope of its own: the extension gives it one because it is audited
    @Audited
    static class Ledger {
        @AutoInject
        Clock clock;

        String stamp() {
            return "stamped at " + clock.now();
        }
    }

    // audited, but with no field marked for injection
    @Audited
    static class Journal {
        String name() {
            return "journal";
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
    void anEnhancementMakesAnAuditedClassABean() {
        assertNotNull(context.getBean(Journal.class));
        assertEquals("journal", context.getBean(Journal.class).name());
    }

    @Test
    void theScopeTheEnhancementAddedIsTheScopeTheBeanIsIn() {
        Bean<?> journal = CDI.current().getBeanContainer().getBeans(Journal.class).iterator().next();
        assertEquals(ApplicationScoped.class, journal.getScope());
    }

    @Test
    void anEnhancementMakesAMarkedFieldAnInjectionPoint() {
        Ledger ledger = context.getBean(Ledger.class);
        // the ledger is reached through a client proxy — the extension gave it a normal scope — so the
        // injected field is read through a method rather than directly
        assertEquals("stamped at noon", ledger.stamp(), "the extension should have made the clock an injection point");
    }

    @Test
    void aSynthesisAddsABeanNoClassDeclares() {
        io.micronaut.cdi.test.extension.AuditLog log =
            context.getBean(io.micronaut.cdi.test.extension.AuditLog.class);
        assertNotNull(log);
        // the name is what the extension attached to the bean, read back by the creator it named
        assertEquals("the audit log", log.name());
    }

    @Test
    void aSyntheticBeanIsResolvedThroughTheContainerToo() {
        assertEquals("the audit log", CDI.current()
            .select(io.micronaut.cdi.test.extension.AuditLog.class).get().name());
    }

    @Test
    void theRegistrationPhaseIsToldAboutTheSyntheticBean() {
        // the audit log has no class that was compiled, so the compiler could not describe it: the optional
        // module that reads a class back is what describes it, and this project has that module
        context.getBean(io.micronaut.cdi.test.extension.AuditLog.class);
        assertTrue(io.micronaut.cdi.test.extension.AuditingExtension.REGISTERED.stream()
                .anyMatch(described -> described.contains("scope jakarta.enterprise.context.ApplicationScoped")),
            "expected the synthetic bean to have been described, got "
                + io.micronaut.cdi.test.extension.AuditingExtension.REGISTERED);
    }

    @Test
    void aClassTheExtensionDidNotAskForIsLeftAlone() {
        // the clock says what it is itself, and the enhancement of audited classes did not touch it
        assertTrue(CDI.current().getBeanContainer().getBeans(Clock.class).iterator().next()
            .getQualifiers().stream().anyMatch(q -> q.annotationType().getName()
                .equals("jakarta.enterprise.inject.Default")));
    }
}
