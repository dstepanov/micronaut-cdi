package io.micronaut.cdi.test.groovy

import io.micronaut.cdi.test.extension.kept.KeptDescriptionReport
import io.micronaut.context.ApplicationContext
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals

/**
 * The descriptions of a bean and an observer an extension kept from the registration phase read in synthesis, which
 * runs once the compilation is finished, as they did when they were handed out; validation fails the compilation
 * unless it reads the same.
 */
class KeptDescriptionsUnderGroovyTest {

    @Test
    void theKeptDescriptionsReadInSynthesisAsTheyWereRegistered() {
        ApplicationContext context = ApplicationContext.run()
        try {
            assertEquals('KeptProbe types=[KeptProbe, Object] qualifiers=[Any, KeptQualifier, Named] normalScope=false methods=[keptMethod, keptObserve]; enhanced KeptProbe methods=[keptMethod, keptObserve]; observer KeptProbe.keptObserve event=KeptEvent parameters=[event:KeptEvent]', context.getBean(KeptDescriptionReport).text())
        } finally {
            context.close()
        }
    }
}
