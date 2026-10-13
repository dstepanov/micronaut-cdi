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
