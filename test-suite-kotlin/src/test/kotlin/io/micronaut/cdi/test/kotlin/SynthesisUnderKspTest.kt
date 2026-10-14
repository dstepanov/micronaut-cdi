package io.micronaut.cdi.test.kotlin

import io.micronaut.cdi.internal.runtime.CdiBeanContainer
import io.micronaut.cdi.test.PlainScannedBean
import io.micronaut.cdi.test.extension.ScannedLibraryBean
import io.micronaut.cdi.test.extension.Zesty
import io.micronaut.cdi.spi.CdiReflection
import io.micronaut.cdi.test.extension.Tended
import io.micronaut.cdi.test.extension.TendedContext
import io.micronaut.cdi.test.extension.signpost.Arrival
import io.micronaut.cdi.test.extension.signpost.Signpost
import io.micronaut.cdi.test.extension.signpost.SignpostExtension
import io.micronaut.context.ApplicationContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The synthesis, registration and validation phases of a build compatible extension run in a Kotlin compilation
 * through KSP as they do in a Java one: the extension would have failed this compilation had its synthetic bean
 * not been registered, and the container has what it described, with no reflection module on the classpath.
 */
class SynthesisUnderKspTest {

    @Test
    fun theSyntheticBeanIsCreatedFromItsRecordedParameter() {
        ApplicationContext.run().use { context ->
            assertFalse(context.findBean(CdiReflection::class.java).isPresent)
            assertEquals("this way", context.getBean(Signpost::class.java).text)
            val container = context.getBean(CdiBeanContainer::class.java)
            assertEquals(1, container.getBeans("signpost").size)
            assertEquals("this way", container.createInstance().select(Signpost::class.java).get().text)
        }
    }

    @Test
    fun theSyntheticObserverIsNotified() {
        ApplicationContext.run().use { context ->
            val before = SignpostExtension.ArrivalObserver.OBSERVED.size
            context.getBean(CdiBeanContainer::class.java).event.select(Arrival::class.java).fire(Arrival("kotlin"))
            assertEquals(before + 1, SignpostExtension.ArrivalObserver.OBSERVED.size)
            assertEquals("welcome kotlin", SignpostExtension.ArrivalObserver.OBSERVED[before])
        }
    }

    @Test
    fun aNestedClassNamedWithADollarSignIsScannedAndQualifies() {
        // the extension names both by a binary name, with the dollar sign a Kotlin string would interpolate
        ApplicationContext.run().use { context ->
            val container = context.getBean(CdiBeanContainer::class.java)
            assertEquals(1, container.getBeans(PlainScannedBean.Nested::class.java).size)
            assertTrue(container.isQualifier(Zesty.Nested::class.java))
        }
    }

    @Test
    fun aScannedClassOfALibraryIsRegisteredAsABean() {
        // no compilation of the application compiles it, and KSP reads no import of it
        ApplicationContext.run().use { context ->
            val container = context.getBean(CdiBeanContainer::class.java)
            assertEquals(1, container.getBeans(ScannedLibraryBean::class.java).size)
            assertEquals("library", container.createInstance().select(ScannedLibraryBean::class.java).get().name())
            // its injection point is resolved by the container, and its producer is a bean, as of a class compiled here
            val field = context.getBeanDefinition(ScannedLibraryBean::class.java).injectedFields.single { it.name == "manager" }
            assertTrue(field.annotationMetadata.hasStereotype(io.micronaut.context.annotation.ResolveWith::class.java))
            assertTrue(container.createInstance().select(ScannedLibraryBean::class.java).get().manager != null)
            assertEquals("produced by the library", container.createInstance()
                .select(io.micronaut.cdi.test.extension.ScannedLibraryProduct::class.java).get().name())
        }
    }

    @Test
    fun theContextAnExtensionRegisteredServesItsScope() {
        ApplicationContext.run().use { context ->
            val contexts = context.getBean(CdiBeanContainer::class.java).getContexts(Tended::class.java)
            assertEquals(1, contexts.size)
            assertTrue(contexts.first() is TendedContext)
        }
    }
}
