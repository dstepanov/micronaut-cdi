package io.micronaut.cdi.test.kotlin

import io.micronaut.cdi.internal.runtime.CdiBeanContainer
import io.micronaut.context.ApplicationContext
import io.micronaut.core.annotation.AnnotationValue
import jakarta.enterprise.context.Dependent
import jakarta.enterprise.util.Nonbinding
import jakarta.inject.Qualifier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A qualifier whose member is annotated with a second qualifier, whose member names the first.
 */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class Clockwise(@get:Nonbinding @Anticlockwise val turns: String = "")

/**
 * The second of two qualifiers whose members are annotated with each other.
 */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class Anticlockwise(@get:Nonbinding @Clockwise(turns = "one") val turns: String = "")

/**
 * A bean qualified by the first of the two.
 */
@Dependent
@Clockwise(turns = "two")
open class Dial

/**
 * Two qualifier interfaces whose members are annotated with each other, compiled through KSP: reading the
 * non-binding members of one reads the annotations of its members, which names the other, whose members name the
 * first. The compilation has to end, and the bean has to resolve by a value of the non-binding member it was not
 * given.
 */
class MutuallyAnnotatedQualifierMembersUnderKspTest {

    @Test
    fun aQualifierWhoseMembersNameEachOtherCompilesAndResolves() {
        ApplicationContext.run().use { context ->
            val dial = context.getBean(CdiBeanContainer::class.java).createInstance()
                .select(Dial::class.java, AnnotationValue.builder(Clockwise::class.java).member("turns", "other").build())
            assertEquals(Dial::class.java, dial.get().javaClass)
        }
    }
}
