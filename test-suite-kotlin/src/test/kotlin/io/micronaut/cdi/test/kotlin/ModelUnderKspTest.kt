package io.micronaut.cdi.test.kotlin

import io.micronaut.cdi.test.extension.Recorded
import io.micronaut.cdi.test.extension.Seen
import io.micronaut.context.ApplicationContext
import jakarta.enterprise.context.ApplicationScoped
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The language model reaches a build compatible extension in a Kotlin compilation the same way it does in a
 * Java one: from Micronaut's AST, with nothing read from a compiler.
 */
class ModelUnderKspTest {

    @Test
    fun theExtensionReadTheKotlinClassThroughTheModel() {
        ApplicationContext.run().use { context ->
            val metadata = context.getBeanDefinition(Marked::class.java).annotationMetadata
            val seen = metadata.stringValues(Seen::class.java, "classAnnotations").toSet()
            assertTrue(seen.contains(Recorded::class.java.name), seen.toString())
            assertTrue(seen.contains(ApplicationScoped::class.java.name), seen.toString())
            // the mapped scope annotation is reported too: nothing narrows the model here
            assertTrue(seen.contains("io.micronaut.cdi.annotation.CdiScope"), seen.toString())
        }
    }
}
