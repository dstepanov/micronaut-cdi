package io.micronaut.cdi.test.groovy

import io.micronaut.cdi.test.extension.Recorded
import jakarta.enterprise.context.ApplicationScoped

/**
 * A Groovy class the recording extension reads through the language model as it compiles.
 */
@Recorded
@ApplicationScoped
class Marked {
    String name = ""
    List<String> names = []
}
