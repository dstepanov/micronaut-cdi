package io.micronaut.cdi.test.groovy

import io.micronaut.cdi.internal.metadata.CdiTypeIndex
import io.micronaut.context.ApplicationContext
import io.micronaut.core.annotation.AnnotationValue
import io.micronaut.inject.qualifiers.Qualifiers
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * The generic hierarchy of a class no definition describes is recorded on a definition the Groovy compilation
 * registers, placed where the classes it refers to can be referred to: a package-private supertype from its own
 * package.
 */
class TypeIndexUnderGroovyTest {

    @Test
    void aPackagePrivateSupertypeIsRecordedAsItsClass() {
        try (ApplicationContext context = ApplicationContext.run()) {
            AnnotationValue<?> entry = null
            for (def definition : context.getBeanDefinitions(Qualifiers.byStereotype(CdiTypeIndex))) {
                for (def candidate : definition.getAnnotationMetadata().getAnnotation(CdiTypeIndex).getAnnotations('value')) {
                    if (candidate.stringValue('name').orElse(null) == 'io.micronaut.cdi.test.groovy.hidden.HiddenPayload') {
                        entry = candidate
                    }
                }
            }
            assertNotNull(entry, 'the payload is recorded')
            def supertype = entry.getAnnotations('supertypes')[0]
            assertEquals('io.micronaut.cdi.test.groovy.hidden.HiddenBase', supertype.stringValue('value').orElse(null))
            assertTrue(supertype.classValue('value').isPresent(),
                'the package-private supertype is recorded as a class, from its own package')
        }
    }
}
