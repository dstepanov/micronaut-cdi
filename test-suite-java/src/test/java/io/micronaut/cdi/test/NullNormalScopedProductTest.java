package io.micronaut.cdi.test;

import io.micronaut.context.ApplicationContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.IllegalProductException;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A producer of a bean in a normal scope may not produce null (CDI 4.1 section 3.2): the instance is shared, and
 * a null cannot be. The bean exists, so its reference is injected; what is reached through it is the producer's
 * null, which is an {@link IllegalProductException} rather than a bean that is not there - in an SE container
 * too, whose archive holds the client proxy of a produced bean wherever the produced type is declared.
 */
class NullNormalScopedProductTest {

    @jakarta.inject.Qualifier
    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
    @interface Chore {
    }

    @jakarta.inject.Qualifier
    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
    @interface Errand {
    }

    @Dependent
    static class ErrandProducer {
        static final java.util.List<String> RAN = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Produces
        @ApplicationScoped
        @Errand
        Runnable errand() {
            return () -> RAN.add("ran");
        }
    }

    @Dependent
    static class Runner {
        @Inject
        @Errand
        Runnable errand;
    }

    @Dependent
    static class ChoreProducer {
        @Produces
        @ApplicationScoped
        @Chore
        Runnable chore() {
            return null;
        }
    }

    @Dependent
    static class Worker {
        @Inject
        @Chore
        Runnable chore;
    }

    @Test
    void aNullProductOfANormalScopeIsAnIllegalProductInAnSeContainer() {
        try (jakarta.enterprise.inject.se.SeContainer container = jakarta.enterprise.inject.se.SeContainerInitializer
            .newInstance().disableDiscovery().addBeanClasses(ChoreProducer.class, Worker.class).initialize()) {
            Worker worker = container.select(Worker.class).get();
            assertThrows(IllegalProductException.class, () -> worker.chore.run());
        }
    }

    @Test
    void aNormalScopedProductOfAClassOutsideTheArchiveIsInTheArchiveOfItsProducer() {
        // the client proxy of the produced Runnable is of the produced type, and is in the archive the producer
        // is in
        ErrandProducer.RAN.clear();
        try (jakarta.enterprise.inject.se.SeContainer container = jakarta.enterprise.inject.se.SeContainerInitializer
            .newInstance().disableDiscovery().addBeanClasses(ErrandProducer.class, Runner.class).initialize()) {
            container.select(Runner.class).get().errand.run();
            org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of("ran"), ErrandProducer.RAN);
        }
    }

    @Test
    void aNullProductOfANormalScopeIsAnIllegalProduct() {
        try (ApplicationContext context = ApplicationContext.run()) {
            Worker worker = context.getBean(Worker.class);
            assertThrows(IllegalProductException.class, () -> worker.chore.run());
        }
    }
}
