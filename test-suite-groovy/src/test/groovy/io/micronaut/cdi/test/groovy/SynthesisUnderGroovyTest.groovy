package io.micronaut.cdi.test.groovy

import io.micronaut.cdi.internal.runtime.CdiBeanContainer
import io.micronaut.cdi.internal.runtime.ExtensionQualifiers
import io.micronaut.cdi.test.PlainScannedBean
import io.micronaut.cdi.test.extension.Zesty
import io.micronaut.cdi.spi.CdiReflection
import io.micronaut.cdi.test.extension.Tended
import io.micronaut.cdi.test.extension.TendedContext
import io.micronaut.cdi.test.extension.signpost.Arrival
import io.micronaut.cdi.test.extension.signpost.Signpost
import io.micronaut.cdi.test.extension.signpost.SignpostExtension
import io.micronaut.context.ApplicationContext
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * The synthesis, registration and validation phases of a build compatible extension run in a Groovy compilation
 * as they do in a Java one: the extension would have failed this compilation had its synthetic bean not been
 * registered, and the container has what it described, with no reflection module on the classpath.
 */
class SynthesisUnderGroovyTest {

    @Test
    void theSyntheticBeanIsCreatedFromItsRecordedParameter() {
        ApplicationContext context = ApplicationContext.run()
        try {
            assertFalse(context.findBean(CdiReflection).isPresent())
            assertEquals("this way", context.getBean(Signpost).text())
            CdiBeanContainer container = context.getBean(CdiBeanContainer)
            assertEquals(1, container.getBeans("signpost").size())
            assertEquals("this way", container.createInstance().select(Signpost).get().text())
        } finally {
            context.close()
        }
    }

    @Test
    void aNestedClassNamedWithADollarSignIsScannedAndQualifies() {
        // the generated sources name both by a binary name, which a Groovy string would otherwise interpolate
        ApplicationContext context = ApplicationContext.run()
        try {
            CdiBeanContainer container = context.getBean(CdiBeanContainer)
            assertEquals(1, container.getBeans(PlainScannedBean.Nested).size())
            assertTrue(ExtensionQualifiers.isKnownQualifier(Zesty.Nested.name))
        } finally {
            context.close()
        }
    }

    @Test
    void theSyntheticObserverIsNotified() {
        ApplicationContext context = ApplicationContext.run()
        try {
            int before = SignpostExtension.ArrivalObserver.OBSERVED.size()
            context.getBean(CdiBeanContainer).getEvent().select(Arrival).fire(new Arrival("groovy"))
            assertEquals(before + 1, SignpostExtension.ArrivalObserver.OBSERVED.size())
            assertEquals("welcome groovy", SignpostExtension.ArrivalObserver.OBSERVED.get(before))
        } finally {
            context.close()
        }
    }

    @Test
    void theContextAnExtensionRegisteredServesItsScope() {
        ApplicationContext context = ApplicationContext.run()
        try {
            def contexts = context.getBean(CdiBeanContainer).getContexts(Tended)
            assertEquals(1, contexts.size())
            assertTrue(contexts.first() instanceof TendedContext)
        } finally {
            context.close()
        }
    }
}
