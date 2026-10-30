package io.micronaut.cdi.test.groovy

import io.micronaut.cdi.internal.runtime.CdiBeanContainer
import io.micronaut.cdi.test.extension.ScannedLibraryBean
import io.micronaut.cdi.test.extension.ScannedLibraryNote
import io.micronaut.cdi.test.extension.ScannedLibraryProbe
import io.micronaut.cdi.test.extension.ScannedLibraryProduct
import io.micronaut.cdi.test.extension.ScannedLibrarySignal
import io.micronaut.context.ApplicationContext
import jakarta.enterprise.inject.Instance
import jakarta.enterprise.inject.literal.NamedLiteral
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * The scanned class of a library a Groovy compilation registers a bean of is the bean a class of the compilation
 * is: its observer is notified and its disposers are invoked through the executable methods of its definition,
 * the producers it overloads by the dimensions of a parameter are told apart, and a qualified field without
 * Inject is no injection point.
 */
class ScannedLibraryUnderGroovyTest {

    private static <T> void createAndDestroy(Instance<T> lookup) {
        Instance.Handle<T> handle = lookup.getHandle()
        handle.get()
        handle.destroy()
    }

    @Test
    void theObserverAndTheDisposerOfTheLibraryClassAreInvoked() {
        ApplicationContext context = ApplicationContext.run()
        try {
            CdiBeanContainer container = context.getBean(CdiBeanContainer)
            container.getEvent().select(ScannedLibrarySignal).fire(new ScannedLibrarySignal("groovy"))
            createAndDestroy(container.createInstance().select(ScannedLibraryProduct))
            assertEquals(["observed groovy", "disposed produced by the library"], context.getBean(ScannedLibraryProbe).seen())
        } finally {
            context.close()
        }
    }

    @Test
    void overloadsToldApartByTheDimensionsOfAParameterKeepTheirOwnDisposers() {
        ApplicationContext context = ApplicationContext.run()
        try {
            Instance<ScannedLibraryNote> notes = context.getBean(CdiBeanContainer).createInstance()
                .select(ScannedLibraryNote)
            createAndDestroy(notes.select(NamedLiteral.of("single")))
            createAndDestroy(notes.select(NamedLiteral.of("several")))
            assertEquals(["disposed note several"], context.getBean(ScannedLibraryProbe).seen().findAll { it.startsWith("disposed note") })
        } finally {
            context.close()
        }
    }

    @Test
    void aQualifiedFieldWithoutInjectKeepsItsValue() {
        ApplicationContext context = ApplicationContext.run()
        try {
            assertEquals("kept", context.getBean(CdiBeanContainer).createInstance()
                .select(ScannedLibraryBean).get().kept)
        } finally {
            context.close()
        }
    }

    @Test
    @Disabled("Micronaut Core #14217 (produceBeans of package-private and primitive producers) is not in the published snapshot yet")
    void aPackagePrivateOrPrimitiveProducerOfTheLibraryClassIsABean() {
        ApplicationContext context = ApplicationContext.run()
        try {
            CdiBeanContainer container = context.getBean(CdiBeanContainer)
            assertTrue(container.getBeans("libraryCount").size() == 1, "the primitive producer")
            assertTrue(container.getBeans("libraryHidden").size() == 1, "the package-private producer")
        } finally {
            context.close()
        }
    }
}
