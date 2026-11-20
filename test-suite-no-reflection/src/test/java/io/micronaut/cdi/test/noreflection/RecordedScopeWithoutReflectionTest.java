package io.micronaut.cdi.test.noreflection;

import io.micronaut.context.ApplicationContext;
import jakarta.enterprise.context.NormalScope;
import jakarta.enterprise.inject.spi.BeanContainer;
import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A normal scope of the application's own that a compiled bean carries is a scope, and a normal one, to the bean
 * container without the reflection module: the processor recorded both with the bean (CDI 4.1 section 11.3.10).
 */
class RecordedScopeWithoutReflectionTest {

    @NormalScope
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
    @interface WorkScoped {
    }

    @WorkScoped
    public static class Job {
    }

    @Test
    void aRecordedNormalScopeIsAScopeWithoutReflection() {
        try (ApplicationContext context = ApplicationContext.run()) {
            BeanContainer container = context.getBean(BeanContainer.class);
            assertTrue(container.isScope(WorkScoped.class));
            assertTrue(container.isNormalScope(WorkScoped.class));
        }
    }
}
