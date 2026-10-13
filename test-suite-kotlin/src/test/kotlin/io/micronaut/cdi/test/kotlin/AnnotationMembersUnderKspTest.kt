package io.micronaut.cdi.test.kotlin

import io.micronaut.cdi.test.extension.Seen
import io.micronaut.context.ApplicationContext
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * KSP answers the members of an annotation interface as its properties and no methods, a Java annotation on the
 * classpath included; the language model an extension reads lists them all the same, and tells an enum member
 * by the type its declaration returns.
 */
class AnnotationMembersUnderKspTest {

    @Test
    fun theMembersOfAnAnnotationAreReadAsKspAnswersThem() {
        ApplicationContext.run().use { context ->
            val metadata = context.getBeanDefinition(Graded::class.java).annotationMetadata
            val members = metadata.stringValues(Seen::class.java, "annotationMembers").toSet()
            assertTrue(members.contains("Tier.value:ENUM"), members.toString())
            assertTrue(members.contains("Tier#value"), members.toString())
        }
    }
}
