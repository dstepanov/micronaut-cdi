package io.micronaut.cdi.test.kotlin

import io.micronaut.cdi.test.extension.Flavor
import io.micronaut.context.ApplicationContext
import io.micronaut.context.annotation.ResolveWith
import io.micronaut.core.annotation.AnnotationMetadata
import io.micronaut.inject.BeanDefinition
import jakarta.enterprise.context.Dependent
import jakarta.enterprise.inject.Instance
import jakarta.enterprise.inject.Produces
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@Dependent
open class Pastry

@Flavor("sweet")
@Dependent
open class SweetPastry : Pastry()

/**
 * The one bean of the specification of the type of a list of pastries: the container resolves a point of that type
 * to it, where Micronaut would collect every pastry.
 */
@Dependent
open class Display {
    @Produces
    @Dependent
    open fun window(): List<Pastry> = WINDOW

    companion object {
        val WINDOW: List<Pastry> = listOf(SweetPastry())
    }
}

@Dependent
open class Bakery @Inject constructor(val made: Pastry) {
    @Inject
    lateinit var displayed: Pastry

    @Inject
    lateinit var pastries: Instance<Pastry>

    @Inject
    lateinit var window: List<Pastry>

    @set:Inject
    lateinit var throughTheSetter: Pastry
}

/**
 * Every injection point of a bean of the specification compiled through KSP is resolved by the container, which
 * the processor marks it to be, while a lookup is left to Micronaut: a property injected as its field or through its
 * setter, whichever KSP compiles it to, and one whose setter alone is annotated.
 */
class InjectionUnderKspTest {

    @Test
    fun theInjectionPointsAreResolvedByTheContainer() {
        ApplicationContext.run().use { context ->
            val definition = context.getBeanDefinition(Bakery::class.java)
            assertTrue(definition.constructor.arguments[0].annotationMetadata.hasStereotype(ResolveWith::class.java))
            assertTrue(heldPoint(definition, "displayed").hasStereotype(ResolveWith::class.java))
            assertTrue(heldPoint(definition, "window").hasStereotype(ResolveWith::class.java))
            assertFalse(heldPoint(definition, "pastries").hasStereotype(ResolveWith::class.java))
            assertTrue(heldPoint(definition, "throughTheSetter").hasStereotype(ResolveWith::class.java))
            val setter = definition.injectedMethods.single { it.name == "setThroughTheSetter" }
            assertTrue(setter.annotationMetadata.hasAnnotation(io.micronaut.cdi.internal.metadata.CdiInjectedMethod::class.java))
            val bakery = context.getBean(Bakery::class.java)
            // the one with the default qualifier, as a lookup resolves it
            assertEquals(Pastry::class.java, bakery.made.javaClass)
            assertEquals(Pastry::class.java, bakery.displayed.javaClass)
            assertEquals(Pastry::class.java, bakery.pastries.get().javaClass)
            assertEquals(Pastry::class.java, bakery.throughTheSetter.javaClass)
            // the bean of the type of the point, rather than every pastry Micronaut would collect
            assertSame(Display.WINDOW, bakery.window)
        }
    }
}

/**
 * The metadata of the point a property is injected through, as the definition holds it: its field, or the parameter
 * of its setter, whichever KSP compiled it to.
 */
private fun heldPoint(definition: BeanDefinition<*>, property: String): AnnotationMetadata {
    val field = definition.injectedFields.singleOrNull { it.name == property }
    if (field != null) {
        return field.annotationMetadata
    }
    val setter = "set" + property.replaceFirstChar { it.uppercase() }
    val method = definition.injectedMethods.singleOrNull { it.name == setter }
    assertNotNull(method, "$property is injected neither as a field nor through $setter")
    return method!!.arguments[0].annotationMetadata
}
