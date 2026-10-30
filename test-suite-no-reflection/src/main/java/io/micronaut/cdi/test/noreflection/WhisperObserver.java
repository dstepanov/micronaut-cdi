package io.micronaut.cdi.test.noreflection;

import jakarta.enterprise.inject.build.compatible.spi.Parameters;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticObserver;
import jakarta.enterprise.inject.spi.EventContext;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Observes a whisper, reading the payload alone.
 */
public final class WhisperObserver implements SyntheticObserver<Whisper> {

    /**
     * The payloads observed.
     */
    public static final List<String> OBSERVED = new CopyOnWriteArrayList<>();

    @Override
    public void observe(EventContext<Whisper> event, Parameters params) {
        OBSERVED.add(event.getEvent().payload());
    }
}
