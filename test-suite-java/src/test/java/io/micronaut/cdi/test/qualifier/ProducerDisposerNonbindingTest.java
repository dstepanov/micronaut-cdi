package io.micronaut.cdi.test.qualifier;

import io.micronaut.context.ApplicationContext;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.enterprise.util.AnnotationLiteral;
import jakarta.enterprise.util.Nonbinding;
import jakarta.inject.Qualifier;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A disposer is bound to its producer by the rules of typesafe resolution (section 3.4.3), in which a member marked
 * {@code Nonbinding} takes no part: a disposer parameter that writes another value of it disposes of what the
 * producer produced.
 */
class ProducerDisposerNonbindingTest {

    static final List<String> DISPOSED = new ArrayList<>();

    @Qualifier
    @Retention(RetentionPolicy.RUNTIME)
    @interface Batch {

        int value();

        @Nonbinding String remark() default "";
    }

    static final class BatchLiteral extends AnnotationLiteral<Batch> implements Batch {

        private final int value;
        private final String remark;

        BatchLiteral(int value, String remark) {
            this.value = value;
            this.remark = remark;
        }

        @Override
        public int value() {
            return value;
        }

        @Override
        public String remark() {
            return remark;
        }
    }

    record Lot(String name) {
    }

    @Dependent
    static class Lots {

        @Produces
        @Batch(value = 1, remark = "produced")
        Lot first() {
            return new Lot("first");
        }

        void dispose(@Disposes @Batch(value = 1, remark = "disposed") Lot lot) {
            DISPOSED.add(lot.name());
        }
    }

    @Test
    void theDisposerOfANonbindingMemberDisposesOfWhatTheProducerProduced() {
        DISPOSED.clear();
        try (ApplicationContext context = ApplicationContext.run()) {
            Instance<Lot> lots = context.getBean(BeanManager.class).createInstance()
                .select(Lot.class, new BatchLiteral(1, "looked up"));
            Lot lot = lots.get();
            lots.destroy(lot);
            assertEquals(List.of("first"), DISPOSED);
        }
    }
}
