package io.micronaut.cdi.test.synthetic;

import io.micronaut.cdi.internal.runtime.CdiBeanContainer;
import io.micronaut.cdi.test.extension.Zest;
import io.micronaut.cdi.test.extension.mapscope.Twin;
import io.micronaut.context.ApplicationContext;
import jakarta.enterprise.inject.AmbiguousResolutionException;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.spi.Bean;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Two synthetic beans are two beans. CdiBean compares by the class of its definition, which every
 * runtime definition shares.
 */
class SyntheticBeanIdentityTest {

    /**
     * The two flavoured zests of ReviewScenariosExtension, told apart by a qualifier member.
     */
    @Test
    void everySyntheticBeanOfTheTypeIsReported() {
        try (ApplicationContext context = ApplicationContext.run()) {
            CdiBeanContainer container = context.getBean(CdiBeanContainer.class);
            Set<Bean<?>> beans = container.getBeans(Zest.class, Any.Literal.INSTANCE);

            assertEquals(2, beans.size(), "the sweet and the sour zest: " + beans);
        }
    }

    /**
     * Section 5.2.2: two beans of the same type and qualifiers are an ambiguous resolution.
     */
    @Test
    void twoSyntheticBeansOfTheSameTypeAndQualifiersAreAmbiguous() {
        try (ApplicationContext context = ApplicationContext.run()) {
            CdiBeanContainer container = context.getBean(CdiBeanContainer.class);
            Set<Bean<?>> beans = container.getBeans(Twin.class);

            assertEquals(2, beans.size(), "both twins: " + beans);
            assertThrows(AmbiguousResolutionException.class, () -> container.resolve(beans));
        }
    }
}
