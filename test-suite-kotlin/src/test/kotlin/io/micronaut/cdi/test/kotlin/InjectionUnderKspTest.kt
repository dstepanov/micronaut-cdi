package io.micronaut.cdi.test.kotlin

import io.micronaut.cdi.test.extension.Flavor
import io.micronaut.context.ApplicationContext
import io.micronaut.context.annotation.ResolveWith
import jakarta.enterprise.context.Dependent
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@Dependent
open class Pastry

@Flavor("sweet")
@Dependent
open class SweetPastry : Pastry()

@Dependent
open class Bakery @Inject constructor(val made: Pastry) {
    @Inject
    lateinit var displayed: Pastry

    @Inject
    lateinit var pastries: Instance<Pastry>
}

/**
 * Every injection point of a bean of the specification compiled through KSP is resolved by the container, which
 * the processor marks it to be, while a lookup is left to Micronaut.
 */
class InjectionUnderKspTest {

    @Test
    fun theInjectionPointsAreResolvedByTheContainer() {
        ApplicationContext.run().use { context ->
            val definition = context.getBeanDefinition(Bakery::class.java)
            assertTrue(definition.constructor.arguments[0].annotationMetadata.hasStereotype(ResolveWith::class.java))
            val fields = definition.injectedFields.associateBy { it.name }
            val methods = definition.injectedMethods.associateBy { it.name }
            assertTrue(markedIn(fields["displayed"]?.annotationMetadata, methods["setDisplayed"]?.arguments?.get(0)?.annotationMetadata))
            assertFalse(markedIn(fields["pastries"]?.annotationMetadata, methods["setPastries"]?.arguments?.get(0)?.annotationMetadata))
            val bakery = context.getBean(Bakery::class.java)
            // the one with the default qualifier, as a lookup resolves it
            assertEquals(Pastry::class.java, bakery.made.javaClass)
            assertEquals(Pastry::class.java, bakery.displayed.javaClass)
            assertEquals(Pastry::class.java, bakery.pastries.get().javaClass)
        }
    }
}

// a lateinit property is injected as its field or through its setter, as KSP compiles it
private fun markedIn(vararg metadata: io.micronaut.core.annotation.AnnotationMetadata?): Boolean =
    metadata.any { it != null && it.hasStereotype(ResolveWith::class.java) }
