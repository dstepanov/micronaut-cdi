package io.micronaut.cdi.test.groovy

import io.micronaut.cdi.test.extension.Flavor
import io.micronaut.context.ApplicationContext
import io.micronaut.context.annotation.ResolveWith
import jakarta.enterprise.context.Dependent
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * Every injection point of a bean of the specification compiled by the Groovy compiler is resolved by the
 * container, which the processor marks it to be, while a lookup is left to Micronaut.
 */
class InjectionUnderGroovyTest {

    @Dependent
    static class Pastry {
    }

    @Flavor("sweet")
    @Dependent
    static class SweetPastry extends Pastry {
    }

    @Dependent
    static class Bakery {
        final Pastry made

        @Inject
        Pastry displayed

        @Inject
        Instance<Pastry> pastries

        @Inject
        Bakery(Pastry made) {
            this.made = made
        }
    }

    @Test
    void theInjectionPointsAreResolvedByTheContainer() {
        try (ApplicationContext context = ApplicationContext.run()) {
            def definition = context.getBeanDefinition(Bakery)
            assertTrue(definition.constructor.arguments[0].annotationMetadata.hasStereotype(ResolveWith))
            def fields = definition.injectedFields.collectEntries { [(it.name): it] }
            def methods = definition.injectedMethods.collectEntries { [(it.name): it] }
            def displayed = fields.displayed ?: methods.setDisplayed
            def pastries = fields.pastries ?: methods.setPastries
            assertTrue((displayed.annotationMetadata.hasStereotype(ResolveWith)
                || displayed.arguments[0].annotationMetadata.hasStereotype(ResolveWith)))
            assertFalse((pastries.annotationMetadata.hasStereotype(ResolveWith)
                || pastries.arguments[0].annotationMetadata.hasStereotype(ResolveWith)))
            def bakery = context.getBean(Bakery)
            // the one with the default qualifier, as a lookup resolves it
            assertEquals(Pastry, bakery.made.getClass())
            assertEquals(Pastry, bakery.displayed.getClass())
            assertEquals(Pastry, bakery.pastries.get().getClass())
        }
    }
}
