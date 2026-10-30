package io.micronaut.cdi.test.customscope;

import io.micronaut.cdi.internal.runtime.CdiBeanContainer;
import io.micronaut.cdi.test.extension.mapscope.MapScoped;
import io.micronaut.context.ApplicationContext;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.spi.Bean;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A producer in a normal scope an extension provides a context for is recorded in that scope, as a class in it is,
 * and what it produces is held by that context.
 */
class ProducerCustomScopeTest {

    public static class Gauge {
        public String read() {
            return "gauge";
        }
    }

    @Dependent
    public static class GaugeProducer {
        @Produces
        @MapScoped
        Gauge gauge() {
            return new Gauge();
        }
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void theProducedBeanIsInTheScopeItsProducerDeclares() {
        try (ApplicationContext context = ApplicationContext.run()) {
            CdiBeanContainer container = context.getBean(CdiBeanContainer.class);
            Bean<?> bean = container.resolve((Set) container.getBeans(Gauge.class));
            assertEquals(MapScoped.class, bean.getScope());
            assertEquals("gauge", context.getBean(Gauge.class).read());
            assertEquals(1, MapContexts.of(context).size(), "created in the extension's context");
        }
    }
}
