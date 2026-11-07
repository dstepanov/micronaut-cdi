package io.micronaut.cdi.test.kotlin

import io.micronaut.cdi.test.extension.kept.KeptDescription
import io.micronaut.cdi.test.extension.kept.KeptDescriptionReport
import io.micronaut.cdi.test.extension.kept.KeptEvent
import io.micronaut.context.ApplicationContext
import jakarta.enterprise.context.Dependent
import jakarta.enterprise.event.Observes
import jakarta.inject.Named
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test

/**
 * A bean and an observer whose descriptions an extension keeps from the registration phase.
 */
@KeptDescription
@Dependent
@Named("kept")
open class KeptProbe {

    open fun keptMethod(): String = "kept"

    open fun keptObserve(@Observes event: KeptEvent) {
    }
}

/**
 * The descriptions of a bean and an observer an extension kept from the registration phase read in synthesis as they
 * did when they were handed out: KSP invalidates the elements of a round as the round ends, and synthesis and
 * validation run once the compilation is finished, after the round that described them. Validation fails the
 * compilation unless it reads the same.
 */
class KeptDescriptionsUnderKspTest {

    @Test
    fun theKeptDescriptionsReadInSynthesisAsTheyWereRegistered() {
        ApplicationContext.run().use { context ->
            val read = context.getBean(KeptDescriptionReport::class.java).text.split("; ")
            assertTrue(read[0].startsWith("KeptProbe types=[KeptProbe, Object] qualifiers=["), read[0])
            assertTrue(read[0].endsWith("] normalScope=false methods=[keptMethod, keptObserve]"), read[0])
            assertEquals("observer KeptProbe.keptObserve event=KeptEvent parameters=[event:KeptEvent]", read[2])
        }
    }

    /**
     * What an extension added to a class as it was enhanced is not seen on the class looked up again once its round
     * is over, and a class description kept from the enhancement phase is invalid by then: Micronaut keeps what a
     * visitor annotated by the round's own symbols under KSP.
     */
    @Test
    @Disabled("Micronaut Core keys what a visitor annotated by the symbols of the round under KSP")
    fun whatAnEnhancementAddedIsSeenOnceTheCompilationIsFinished() {
        ApplicationContext.run().use { context ->
            assertEquals("KeptProbe types=[KeptProbe, Object] qualifiers=[Any, KeptQualifier, Named] normalScope=false "
                + "methods=[keptMethod, keptObserve]; enhanced KeptProbe methods=[keptMethod, keptObserve]; "
                + "observer KeptProbe.keptObserve event=KeptEvent parameters=[event:KeptEvent]",
                context.getBean(KeptDescriptionReport::class.java).text)
        }
    }
}
