package io.micronaut.cdi.test.kotlin

import io.micronaut.cdi.internal.runtime.CdiBeanContainer
import io.micronaut.cdi.test.extension.Flavor
import io.micronaut.cdi.test.extension.Ripe
import io.micronaut.context.ApplicationContext
import io.micronaut.core.annotation.AnnotationUtil
import io.micronaut.core.annotation.AnnotationValue
import jakarta.enterprise.context.Dependent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A Kotlin class compiled through KSP.
 */
@Flavor("tart")
@Ripe("yes")
@Dependent
open class Tart

/**
 * The members of a qualifier that take no part in resolution are recorded on a Kotlin class as KSP compiles it as
 * they are on a Java one: the one the annotation interface marks, and the one a build compatible extension marked,
 * each though the class leaves it at its default.
 */
class NonbindingUnderKspTest {

    @Test
    fun theNonbindingMembersAreRecordedOnTheBean() {
        ApplicationContext.run().use { context ->
            val metadata = context.getBeanDefinition(Tart::class.java).annotationMetadata
            for (qualifier in listOf(Flavor::class.java, Ripe::class.java)) {
                val nonbinding = metadata.getAnnotation(qualifier)!!.stringValues(AnnotationUtil.NON_BINDING_ATTRIBUTE)
                assertTrue(nonbinding.contains("note"), qualifier.name + ": " + nonbinding.toList())
            }
            val tart = context.getBean(CdiBeanContainer::class.java).createInstance()
                .select(Tart::class.java, AnnotationValue.builder(Flavor::class.java).value("tart")
                    .member("note", "written").build())
            assertEquals(Tart::class.java, tart.get().javaClass)
        }
    }
}
