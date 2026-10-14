package io.micronaut.cdi.test;

import io.micronaut.cdi.test.extension.RemovableQualifier;
import io.micronaut.context.ApplicationContext;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Default;
import jakarta.enterprise.inject.spi.BeanManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void aScannedNestedClassIsABean() {
        try (ApplicationContext context = ApplicationContext.run()) {
            BeanManager manager = context.getBean(BeanManager.class);
            assertEquals(1, manager.getBeans(PlainScannedBean.Nested.class).size(),
                "the nested class the extension scanned by its binary name is a bean");
        }
    }

    @Test
    void aScannedClassOfALibraryIsABean() {
        try (ApplicationContext context = ApplicationContext.run()) {
            BeanManager manager = context.getBean(BeanManager.class);
            assertEquals(1, manager.getBeans(io.micronaut.cdi.test.extension.ScannedLibraryBean.class).size(),
                "the class of a library the extension scanned is a bean");
            // its injection point is resolved by the container, and its producer is a bean, as of a class compiled here
            io.micronaut.inject.FieldInjectionPoint<?, ?> field = context.getBeanDefinition(
                io.micronaut.cdi.test.extension.ScannedLibraryBean.class).getInjectedFields().stream()
                .filter(point -> point.getName().equals("manager")).findFirst().orElseThrow();
            assertTrue(field.getAnnotationMetadata().hasStereotype(io.micronaut.context.annotation.ResolveWith.class));
            assertEquals("produced by the library", manager.createInstance()
                .select(io.micronaut.cdi.test.extension.ScannedLibraryProduct.class).get().name());
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
