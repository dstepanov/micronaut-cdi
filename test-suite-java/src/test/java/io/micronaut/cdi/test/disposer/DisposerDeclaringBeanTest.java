package io.micronaut.cdi.test.disposer;

import io.micronaut.context.ApplicationContext;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Alternative;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A disposer method is invoked on an instance of the bean that declares it (CDI 4.1 section 3.4.2), told apart
 * by its class from a selected alternative that extends the class: such a subclass is another bean, which
 * declares neither the producer nor the disposer.
 */
class DisposerDeclaringBeanTest {

    static final List<String> DISPOSED_ON = Collections.synchronizedList(new ArrayList<>());

    record Made(String id) {
    }

    @Dependent
    public static class Maker {

        @Produces
        @Dependent
        Made made() {
            return new Made("made");
        }

        void dispose(@Disposes Made made) {
            DISPOSED_ON.add(made.id() + " on " + getClass().getSimpleName());
        }
    }

    @Alternative
    @Priority(1)
    @Dependent
    public static class SpecialMaker extends Maker {
    }

    @ApplicationScoped
    public static class MadeHolder {

        @Inject
        Made made;

        public String use() {
            return made.id();
        }
    }

    @Test
    void theDisposerRunsOnTheBeanThatDeclaresItRatherThanOnASelectedSubclass() {
        DISPOSED_ON.clear();
        try (ApplicationContext context = ApplicationContext.run()) {
            assertEquals("made", context.getBean(MadeHolder.class).use());
        }
        assertEquals(List.of("made on Maker"), DISPOSED_ON);
    }
}
