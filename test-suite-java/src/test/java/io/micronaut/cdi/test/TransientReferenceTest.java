package io.micronaut.cdi.test;

import io.micronaut.context.ApplicationContext;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.TransientReference;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TransientReferenceTest {

    @Dependent
    static class Chef {
        static final List<String> DESTROYED = new ArrayList<>();

        @jakarta.annotation.PreDestroy
        void gone() {
            DESTROYED.add("chef");
        }
    }

    @Dependent
    static class Spoon {
        @Inject
        Spoon(@TransientReference Chef chef) {
        }
    }

    @Dependent
    static class Fork {
        @Inject
        Fork(@io.micronaut.context.annotation.InjectScope Chef chef) {
        }
    }

    @Test
    void nativeInjectScopeDestroysOnCreation() {
        Chef.DESTROYED.clear();
        try (ApplicationContext context = ApplicationContext.run()) {
            context.getBean(Fork.class);
            assertEquals(List.of("chef"), Chef.DESTROYED, "native InjectScope should destroy the chef");
        }
    }

    @Test
    void aTransientReferenceIsDestroyedWhenCreationCompletes() {
        Chef.DESTROYED.clear();
        try (ApplicationContext context = ApplicationContext.run()) {
            context.getBean(Spoon.class);
            assertEquals(List.of("chef"), Chef.DESTROYED,
                "the transient chef should be destroyed as soon as the spoon is made");
        }
    }
}
