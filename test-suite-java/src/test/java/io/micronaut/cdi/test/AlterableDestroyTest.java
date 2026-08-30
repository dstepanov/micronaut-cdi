package io.micronaut.cdi.test;

import io.micronaut.context.ApplicationContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.spi.AlterableContext;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AlterableDestroyTest {

    @ApplicationScoped
    static class Component {
        private String value;

        String getValue() {
            return value;
        }

        void setValue(String value) {
            this.value = value;
        }
    }

    @Test
    void destroyingTheContextualInstanceGivesAFreshOneThroughTheProxy() {
        try (ApplicationContext context = ApplicationContext.run()) {
            BeanManager manager = context.getBean(BeanManager.class);
            Bean<?> bean = manager.getBeans(Component.class).iterator().next();
            Component reference = (Component) manager.getReference(bean, Component.class,
                manager.createCreationalContext(bean));
            assertNull(reference.getValue());
            reference.setValue("foo");
            assertEquals("foo", reference.getValue());

            AlterableContext alterable = (AlterableContext) manager.getContext(bean.getScope());
            alterable.destroy(bean);

            assertNull(reference.getValue(), "the destroyed instance should be gone");
        }
    }
}
