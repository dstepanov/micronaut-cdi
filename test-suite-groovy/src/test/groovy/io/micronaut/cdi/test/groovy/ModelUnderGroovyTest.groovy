package io.micronaut.cdi.test.groovy

import io.micronaut.cdi.test.extension.Recorded
import io.micronaut.cdi.test.extension.Seen
import io.micronaut.context.ApplicationContext
import jakarta.enterprise.context.ApplicationScoped
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * The language model reaches a build compatible extension in a Groovy compilation the same way it does in a
 * Java one: from Micronaut's AST, with nothing read from a compiler.
 */
class ModelUnderGroovyTest {

    @Test
    void theExtensionReadTheGroovyClassThroughTheModel() {
        ApplicationContext.run().withCloseable { context ->
            def metadata = context.getBeanDefinition(Marked).annotationMetadata
            def seen = metadata.stringValues(Seen, "classAnnotations") as Set
            assertTrue(seen.contains(Recorded.name), seen.toString())
            assertTrue(seen.contains(ApplicationScoped.name), seen.toString())
            // the mapped scope annotation is reported too: nothing narrows the model here
            assertTrue(seen.contains("io.micronaut.cdi.annotation.CdiScope"), seen.toString())
        }
    }
}
