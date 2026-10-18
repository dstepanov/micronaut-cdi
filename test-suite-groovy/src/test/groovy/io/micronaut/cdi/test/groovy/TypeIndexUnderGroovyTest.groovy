package io.micronaut.cdi.test.groovy

import io.micronaut.core.type.TypeHierarchies
import io.micronaut.core.type.TypeHierarchy
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNotNull

/**
 * The generic hierarchy of a class no definition describes is recorded as Micronaut's type hierarchy record of the
 * class under the Groovy compiler as well, which Micronaut places where the classes it refers to can be referred to:
 * a package-private supertype from its own package.
 */
class TypeIndexUnderGroovyTest {

    @Test
    void aPackagePrivateSupertypeIsRecordedAsItsClass() {
        Class<?> payload = Class.forName('io.micronaut.cdi.test.groovy.hidden.HiddenPayload')
        TypeHierarchy hierarchy = TypeHierarchies.find(payload).orElse(null)
        assertNotNull(hierarchy, 'the payload is recorded')
        def base = hierarchy.getTypeClosure(hierarchy.declaration)[1]
        assertEquals('io.micronaut.cdi.test.groovy.hidden.HiddenBase', base.type.name,
            'the package-private supertype is recorded as a class, from its own package')
    }
}
