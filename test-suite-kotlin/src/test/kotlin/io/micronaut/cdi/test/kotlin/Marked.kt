package io.micronaut.cdi.test.kotlin

import io.micronaut.cdi.test.extension.Recorded
import jakarta.enterprise.context.ApplicationScoped

/**
 * A Kotlin class the recording extension reads through the language model as KSP compiles it.
 */
@Recorded
@ApplicationScoped
open class Marked {
    var name: String = ""
    var names: List<String> = emptyList()
}
