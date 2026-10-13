package io.micronaut.cdi.test.event;

import io.micronaut.cdi.internal.runtime.CdiBeanContainer;
import io.micronaut.context.ApplicationContext;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Alternative;
import jakarta.enterprise.inject.spi.ObserverMethod;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The priority an observer is notified in is the one its method or its observed parameter declares: the priority
 * that selects the alternative class declaring it is not the observer's (CDI 4.1 section 2.8.3).
 */
class AlternativeObserverPriorityTest {

    public record Ping(String from) {
    }

    @Alternative
    @Priority(10)
    @Dependent
    public static class SelectedObserver {
        void onPing(@Observes Ping ping) {
        }
    }

    @Test
    void anObserverOfAnAlternativeIsNotifiedInTheDefaultPriority() {
        try (ApplicationContext context = ApplicationContext.run()) {
            Set<ObserverMethod<? super Ping>> observers = context.getBean(CdiBeanContainer.class)
                .resolveObserverMethods(new Ping("test"));
            ObserverMethod<? super Ping> observer = observers.stream()
                .filter(candidate -> candidate.getBeanClass() == SelectedObserver.class)
                .findFirst().orElseThrow();
            assertEquals(ObserverMethod.DEFAULT_PRIORITY, observer.getPriority());
        }
    }
}
