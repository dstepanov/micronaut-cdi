package io.micronaut.cdi.test.groovy

import io.micronaut.cdi.test.extension.Flavor
import io.micronaut.context.ApplicationContext
import io.micronaut.context.annotation.ResolveWith
import jakarta.enterprise.context.Dependent
import io.micronaut.core.annotation.AnnotationMetadata
import jakarta.enterprise.inject.Instance
import jakarta.enterprise.inject.Produces
import jakarta.inject.Inject
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertSame
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

    /**
     * The one bean of the specification of the type of a list of pastries: the container resolves a point of that
     * type to it, where Micronaut would collect every pastry.
     */
    @Dependent
    static class Display {
        static final List<Pastry> WINDOW = [new SweetPastry()]

        @Produces
        @Dependent
        List<Pastry> window() {
            WINDOW
        }
    }

    @Dependent
    static class Bakery {
        final Pastry made

        @Inject
        Pastry displayed

        @Inject
        Instance<Pastry> pastries

        @Inject
        List<Pastry> window

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
            assertTrue(heldPoint(fields, methods, 'displayed').hasStereotype(ResolveWith))
            assertTrue(heldPoint(fields, methods, 'window').hasStereotype(ResolveWith))
            assertFalse(heldPoint(fields, methods, 'pastries').hasStereotype(ResolveWith))
            def bakery = context.getBean(Bakery)
            // the one with the default qualifier, as a lookup resolves it
            assertEquals(Pastry, bakery.made.getClass())
            assertEquals(Pastry, bakery.displayed.getClass())
            assertEquals(Pastry, bakery.pastries.get().getClass())
            // the bean of the type of the point, rather than every pastry Micronaut would collect
            assertSame(Display.WINDOW, bakery.window)
        }
    }

    /**
     * The metadata of the point a property is injected through, as the definition holds it: its field, or the
     * parameter of its setter, whichever the Groovy compiler compiled it to.
     */
    private static AnnotationMetadata heldPoint(Map fields, Map methods, String property) {
        def field = fields[property]
        if (field != null) {
            return field.annotationMetadata
        }
        def method = methods['set' + property.capitalize()]
        assertNotNull(method, "$property is injected neither as a field nor through its setter")
        return method.arguments[0].annotationMetadata
    }
}
