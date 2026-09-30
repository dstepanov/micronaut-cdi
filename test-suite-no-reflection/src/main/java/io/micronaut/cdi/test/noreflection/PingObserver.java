package io.micronaut.cdi.test.noreflection;

import jakarta.enterprise.inject.build.compatible.spi.Parameters;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticObserver;
import jakarta.enterprise.inject.spi.EventContext;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Records the events the synthetic observer was notified of.
 */
public final class PingObserver implements SyntheticObserver<Ping> {

    /**
     * What was observed, with the parameter the extension attached to the observer.
     */
    public static final List<String> OBSERVED = new CopyOnWriteArrayList<>();

    @Override
    public void observe(EventContext<Ping> event, Parameters params) {
        OBSERVED.add(event.getEvent().payload() + " heard by " + params.get("listener", String.class));
    }
}
