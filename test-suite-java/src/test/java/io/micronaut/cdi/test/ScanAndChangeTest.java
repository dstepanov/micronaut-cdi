package io.micronaut.cdi.test;

import io.micronaut.cdi.test.extension.RemovableQualifier;
import io.micronaut.context.ApplicationContext;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Default;
import jakarta.enterprise.inject.spi.BeanManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ScanAndChangeTest {

    @Dependent
    @RemovableQualifier
    public static class Stripped {
        public String name() {
            return "stripped";
        }
    }

    @Test
    void aScannedClassIsABean() {
        try (ApplicationContext context = ApplicationContext.run()) {
            BeanManager manager = context.getBean(BeanManager.class);
            assertEquals(1, manager.getBeans(PlainScannedBean.class).size(),
                "the class the extension scanned is a bean");
        }
    }

    @Test
    void aRemovedQualifierIsGone() {
        try (ApplicationContext context = ApplicationContext.run()) {
            BeanManager manager = context.getBean(BeanManager.class);
            assertEquals(1, manager.getBeans(Stripped.class, Default.Literal.INSTANCE).size(),
                "with the qualifier taken off, the bean answers the default qualifier");
        }
    }
}
