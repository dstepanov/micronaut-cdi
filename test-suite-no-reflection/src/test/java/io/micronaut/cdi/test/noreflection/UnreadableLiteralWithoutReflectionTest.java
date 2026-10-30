package io.micronaut.cdi.test.noreflection;

import io.micronaut.context.ApplicationContext;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.enterprise.util.AnnotationLiteral;
import jakarta.inject.Qualifier;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A literal of a qualifier with a binding member that nothing can read without reflection - no generated builder,
 * not one that carries its values - is not selected by as though it were written at its defaults: the lookup fails
 * and says which module reads it.
 */
class UnreadableLiteralWithoutReflectionTest {

    @Qualifier
    @Retention(RetentionPolicy.RUNTIME)
    @interface Zone {
        String value() default "";

        final class Literal extends AnnotationLiteral<Zone> implements Zone {

            private final String value;

            Literal(String value) {
                this.value = value;
            }

            @Override
            public String value() {
                return value;
            }
        }
    }

    interface Locker {
    }

    @Zone
    @Dependent
    static class PlainLocker implements Locker {
    }

    @Test
    void aLiteralNothingCanReadFailsRatherThanSelectingByItsDefaults() {
        try (ApplicationContext ignored = ApplicationContext.run()) {
            Instance<Locker> lockers = CDI.current().select(Locker.class, Any.Literal.INSTANCE);
            UnsupportedOperationException failure = assertThrows(UnsupportedOperationException.class,
                () -> lockers.select(new Zone.Literal("north")).get());
            assertTrue(failure.getMessage().contains("micronaut-cdi-reflection"), failure.getMessage());
        }
    }
}
