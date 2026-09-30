package io.micronaut.cdi.test.creation;

import io.micronaut.cdi.runtime.CdiBeanContainer;
import io.micronaut.context.ApplicationContext;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.spi.Bean;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Section 6.1 has {@code Bean.create()} rethrow the unchecked exception the bean threw, and wrap only a
 * checked one. The exception thrown is the one of the constructor, not the deepest cause it carries.
 */
class CreationExceptionTranslationTest {

    @Dependent
    public static class UncheckedCause {
        public UncheckedCause() {
            throw new IllegalStateException("outer", new IllegalArgumentException("inner"));
        }
    }

    @Dependent
    public static class CheckedCause {
        public CheckedCause() {
            throw new IllegalStateException("outer", new IOException("inner"));
        }
    }

    @Test
    void theExceptionTheConstructorThrewIsRethrownNotItsCause() {
        assertCreationThrowsOuter(UncheckedCause.class);
    }

    @Test
    void anUncheckedExceptionWithACheckedCauseIsNotWrapped() {
        assertCreationThrowsOuter(CheckedCause.class);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void assertCreationThrowsOuter(Class<?> type) {
        try (ApplicationContext context = ApplicationContext.run()) {
            CdiBeanContainer container = context.getBean(CdiBeanContainer.class);
            Bean bean = container.resolve((java.util.Set) container.getBeans(type));
            RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> bean.create(container.createCreationalContext(bean)));

            assertEquals(IllegalStateException.class, thrown.getClass(), type.getSimpleName() + ": " + thrown);
            assertEquals("outer", thrown.getMessage());
        }
    }
}
