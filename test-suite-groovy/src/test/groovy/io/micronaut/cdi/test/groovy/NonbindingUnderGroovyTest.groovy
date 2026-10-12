package io.micronaut.cdi.test.groovy

import io.micronaut.cdi.internal.runtime.CdiBeanContainer
import io.micronaut.cdi.test.extension.Flavor
import io.micronaut.cdi.test.extension.Ripe
import io.micronaut.context.ApplicationContext
import io.micronaut.core.annotation.AnnotationUtil
import io.micronaut.core.annotation.AnnotationValue
import jakarta.enterprise.context.Dependent
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * The members of a qualifier that take no part in resolution are recorded on a Groovy class as they are on a Java
 * one: the one the annotation interface marks, and the one a build compatible extension marked, each though the
 * class leaves it at its default.
 */
class NonbindingUnderGroovyTest {

    @Flavor("tart")
    @Ripe("yes")
    @Dependent
    static class Tart {
    }

    @Test
    void theNonbindingMembersAreRecordedOnTheBean() {
        try (ApplicationContext context = ApplicationContext.run()) {
            def metadata = context.getBeanDefinition(Tart).annotationMetadata
            for (Class qualifier : [Flavor, Ripe]) {
                String[] nonbinding = metadata.getAnnotation(qualifier).stringValues(AnnotationUtil.NON_BINDING_ATTRIBUTE)
                assertTrue(nonbinding.contains("note"), qualifier.name + ": " + nonbinding.toList())
            }
            def tart = context.getBean(CdiBeanContainer).createInstance()
                .select(Tart, AnnotationValue.builder(Flavor).value("tart").member("note", "written").build(),
                    AnnotationValue.builder(Ripe).value("yes").member("note", "written").build())
            assertEquals(Tart, tart.get().getClass())
        }
    }
}
