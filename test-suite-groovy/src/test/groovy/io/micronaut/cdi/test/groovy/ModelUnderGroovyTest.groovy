package io.micronaut.cdi.test.groovy

import io.micronaut.cdi.runtime.CdiBeanContainer
import io.micronaut.cdi.test.PlainScannedBean
import io.micronaut.cdi.test.extension.Recorded
import io.micronaut.cdi.test.extension.Seen
import io.micronaut.cdi.test.extension.Zesty
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
            assertTrue(seen.contains("io.micronaut.cdi.internal.metadata.CdiScope"), seen.toString())
        }
    }

    @Test
    void anAnnotationTheDiscoveryPhaseRegisteredIsAQualifier() {
        // what discovery registers reaches the bean context only through a class the visitor generates as source,
        // which the Groovy compiler compiles in the same compilation since Micronaut Core 5.3 (micronaut-core#13179)
        ApplicationContext.run().withCloseable { context ->
            assertTrue(context.getBean(CdiBeanContainer).isQualifier(Zesty), "Zesty is not a qualifier")
        }
    }

    @Test
    void aClassTheDiscoveryPhaseScannedIsABean() {
        // under Groovy every class of the compilation is visited, and the visitor puts the scope on a scanned class
        // as it goes, so this holds whether or not the generated import is compiled
        ApplicationContext.run().withCloseable { context ->
            assertTrue(context.containsBean(PlainScannedBean), "the scanned class is not a bean")
        }
    }
}
