package io.micronaut.cdi.test.kotlin

import io.micronaut.cdi.test.extension.kept.KeptDescription
import io.micronaut.cdi.test.extension.kept.KeptDescriptionReport
import io.micronaut.cdi.test.extension.kept.KeptEvent
import io.micronaut.context.ApplicationContext
import jakarta.enterprise.context.Dependent
import jakarta.enterprise.event.Observes
import jakarta.inject.Named
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A bean and an observer whose descriptions an extension keeps from the registration phase.
 */
@KeptDescription
@Dependent
@Named("kept")
open class KeptProbe {

    @JvmField
    var keptItems: java.util.List<String>? = null

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
    fun theKeptDescriptionsReadInSynthesisAsTheyWereHandedOut() {
        ApplicationContext.run().use { context ->
            // the qualifiers an enhancement added are checked on their own, below
            val read = context.getBean(KeptDescriptionReport::class.java).text
                .replace(Regex("qualifiers=\\[[^\\]]*\\] "), "")
            assertEquals(EXPECTED.replace("qualifiers=[Any, KeptQualifier, Named] ", ""), read)
        }
    }

    /**
     * What an extension added to a class as it was enhanced is seen on the class looked up again once its round is
     * over: Micronaut keeps what a visitor annotated by the class's name and the member's signature, not by the
     * symbols of the round KSP made.
     */
    @Test
    fun whatAnEnhancementAddedIsSeenOnceTheCompilationIsFinished() {
        ApplicationContext.run().use { context ->
            assertEquals(EXPECTED, context.getBean(KeptDescriptionReport::class.java).text)
        }
    }

    private companion object {
        const val EXPECTED = "KeptProbe types=[KeptProbe, Object] qualifiers=[Any, KeptQualifier, Named] normalScope=false " +
            "methods=[keptMethod, keptObserve]; enhanced KeptProbe methods=[keptMethod, keptObserve]; " +
            "method keptMethod():String of KeptProbe; parameter event:KeptEvent of keptObserve; " +
            "field keptItems:List<String> of KeptProbe; type List<String>; the same class true; " +
            "observer KeptProbe.keptObserve event=KeptEvent parameters=[event:KeptEvent]"
    }
}
