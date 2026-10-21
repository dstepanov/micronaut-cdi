package io.micronaut.cdi.test;

import io.micronaut.cdi.internal.runtime.CdiBeanContainer;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Replaces;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.spi.Bean;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A bean Micronaut's {@code Replaces} replaced is no bean of the context, for the bean manager as for a lookup.
 */
class ReplacedBeanTest {

    public interface Service {
        String name();
    }

    @Dependent
    public static class Original implements Service {
        @Override
        public String name() {
            return "original";
        }
    }

    @Dependent
    @Replaces(Original.class)
    public static class Replacement implements Service {
        @Override
        public String name() {
            return "replacement";
        }
    }

    /**
     * The event the observers of the replaced and the replacing bean observe.
     */
    public static final class Signal {
        final java.util.List<String> heardBy = new java.util.concurrent.CopyOnWriteArrayList<>();
    }

    @Dependent
    public static class OriginalListener {
        void hear(@jakarta.enterprise.event.Observes Signal signal) {
            signal.heardBy.add("original");
        }
    }

    @Dependent
    @Replaces(OriginalListener.class)
    public static class ReplacementListener extends OriginalListener {
        @Override
        void hear(Signal signal) {
            // overridden without @Observes, which makes it no observer (section 4.2)
            signal.heardBy.add("replacement");
        }
    }

    @Test
    void theObserverOfAReplacedBeanIsGoneWithIt() {
        try (ApplicationContext context = ApplicationContext.run()) {
            CdiBeanContainer container = context.getBean(CdiBeanContainer.class);
            Set<Bean<?>> listeners = container.getBeans(OriginalListener.class);
            assertEquals(1, listeners.size(), listeners::toString);
            assertEquals(ReplacementListener.class, listeners.iterator().next().getBeanClass());
            Signal signal = new Signal();
            container.getEvent().select(Signal.class).fire(signal);
            assertEquals(java.util.List.of(), signal.heardBy);
        }
    }

    @Test
    void theBeanManagerAndALookupAgreeThatTheReplacedBeanIsGone() {
        try (ApplicationContext context = ApplicationContext.run()) {
            CdiBeanContainer container = context.getBean(CdiBeanContainer.class);
            Set<Bean<?>> beans = container.getBeans(Service.class);
            assertEquals(1, beans.size(), beans::toString);
            assertEquals(Replacement.class, container.resolve(beans).getBeanClass());
            assertEquals("replacement", container.createInstance().select(Service.class).get().name());
        }
    }
}
