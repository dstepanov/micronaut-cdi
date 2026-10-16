package io.micronaut.cdi.test.kotlin

import io.micronaut.core.type.TypeHierarchies
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
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
 * The generic hierarchy of a class no definition describes is recorded as Micronaut's type hierarchy record of the
 * class under KSP as well. Kotlin has no package-private class a public one may extend: a class private to its file
 * is the only one, and a public class may not expose it as its supertype.
 */
class TypeIndexUnderKspTest {

    @Test
    fun theHierarchyOfAPayloadIsRecorded() {
        val hierarchy = TypeHierarchies.find(KotlinStringBox::class.java).orElse(null)
        assertNotNull(hierarchy, "the payload is recorded")
        val supertype = hierarchy!!.getTypeClosure(hierarchy.declaration)[1]
        assertEquals(KotlinBox::class.java, supertype.type)
        assertEquals(String::class.java, supertype.typeParameters[0].type)
    }
}
