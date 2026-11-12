package io.micronaut.cdi.test.groovy

import io.micronaut.cdi.test.extension.kept.KeptDescription
import io.micronaut.cdi.test.extension.kept.KeptEvent
import jakarta.enterprise.context.Dependent
import jakarta.enterprise.event.Observes
import jakarta.inject.Named

/**
 * A bean and an observer whose descriptions an extension keeps from the registration phase.
 */
@KeptDescription
@Dependent
@Named("kept")
class KeptProbe {

    protected List<String> keptItems = []

    String keptMethod() {
        return "kept"
    }

    void keptObserve(@Observes KeptEvent event) {
    }
}
