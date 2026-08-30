package io.micronaut.cdi.tck;

import io.micronaut.context.ApplicationContext;
import jakarta.enterprise.inject.spi.CDI;
import org.jboss.cdi.tck.tests.event.observer.priority.MoonActivity;
import org.jboss.cdi.tck.tests.event.observer.priority.MoonObservers;
import org.jboss.cdi.tck.tests.event.observer.priority.Moonrise;
import org.jboss.cdi.tck.tests.event.observer.priority.Sunset;
import org.jboss.cdi.tck.tests.event.observer.priority.SunsetObservers;
import org.jboss.cdi.tck.util.ActionSequence;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code org.jboss.cdi.tck.tests.event.observer.priority.ObserverPriorityTest}, run against the kit's own
 * scenarios.
 *
 * <p>Each of them records its own invocation in the kit's {@code ActionSequence}, and what is asserted is the
 * order the sequence ends up in: the order observers are notified in, which section 2.8.5 gives as the ascending
 * order of their priorities.</p>
 */
class ObserverPriorityTckTest {

    private static ApplicationContext context;

    @BeforeAll
    static void startContainer() {
        context = ApplicationContext.run();
    }

    @AfterAll
    static void stopContainer() {
        context.close();
    }

    @BeforeEach
    void resetSequence() {
        ActionSequence.reset();
    }

    /**
     * {@code testObserverPriority}: the observers of one event are notified lowest priority first.
     */
    @Test
    void observersAreNotifiedInAscendingPriorityOrder() {
        CDI.current().getBeanContainer().getEvent().select(Sunset.class).fire(new Sunset());
        assertEquals(
            java.util.List.of(
                SunsetObservers.AsianObserver.class.getName(),
                SunsetObservers.EuropeanObserver.class.getName(),
                SunsetObservers.AmericanObserver.class.getName()),
            ActionSequence.getSequenceData(),
            "observers should be notified in ascending priority order");
    }

    /**
     * {@code testObserverPriorityWithInheritance}: the observers of a supertype of the event are notified in the
     * same ordering as the observers of its own type, since they are all observers of that one event.
     */
    @Test
    void observersOfASupertypeAreOrderedWithTheRest() {
        CDI.current().getBeanContainer().getEvent().select(Moonrise.class).fire(new Moonrise());
        assertEquals(
            java.util.List.of(
                MoonObservers.Observer1.class.getName(),
                MoonObservers.Observer2.class.getName(),
                MoonObservers.Observer3.class.getName(),
                MoonObservers.Observer4.class.getName()),
            ActionSequence.getSequenceData(),
            "an observer of MoonActivity and one of Moonrise are ordered together");
    }

    /**
     * An event of the supertype notifies only the observers of the supertype.
     */
    @Test
    void anEventOfTheSupertypeDoesNotNotifyTheObserversOfTheSubtype() {
        CDI.current().getBeanContainer().getEvent().select(MoonActivity.class).fire(new MoonActivity());
        assertEquals(
            java.util.List.of(
                MoonObservers.Observer1.class.getName(),
                MoonObservers.Observer3.class.getName()),
            ActionSequence.getSequenceData());
    }
}
