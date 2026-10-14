package io.micronaut.cdi.test.kotlin

import io.micronaut.cdi.internal.metadata.CdiTypeIndex
import io.micronaut.context.ApplicationContext
import io.micronaut.core.annotation.AnnotationValue
import io.micronaut.inject.qualifiers.Qualifiers
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A generic class no definition describes, an event payload.
 */
open class KotlinBox<T>

/**
 * A payload whose supertype is parameterized.
 */
class KotlinStringBox : KotlinBox<String>()

/**
 * The generic hierarchy of a class no definition describes is recorded on a definition KSP registers, with no
 * source generated for it. Kotlin has no package-private class a public one may extend: a class private to its
 * file is the only one, and a public class may not expose it as its supertype.
 */
class TypeIndexUnderKspTest {

    @Test
    fun theHierarchyOfAPayloadIsRecorded() {
        ApplicationContext.run().use { context ->
            var entry: AnnotationValue<*>? = null
            for (definition in context.getBeanDefinitions(Qualifiers.byStereotype<Any>(CdiTypeIndex::class.java))) {
                for (candidate in definition.annotationMetadata.getAnnotation(CdiTypeIndex::class.java)!!.getAnnotations<Annotation>("value")) {
                    if (candidate.stringValue("name").orElse(null) == KotlinStringBox::class.java.name) {
                        entry = candidate
                    }
                }
            }
            assertNotNull(entry, "the payload is recorded")
            val supertype = entry!!.getAnnotations<Annotation>("supertypes")[0]
            assertEquals(KotlinBox::class.java.name, supertype.stringValue("value").orElse(null))
            assertTrue(supertype.classValue("value").isPresent)
        }
    }
}
