package io.micronaut.cdi.test.noreflection;

import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.build.compatible.spi.Parameters;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticBeanDisposer;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Records the greetings the container disposed of.
 */
public final class GreetingDisposer implements SyntheticBeanDisposer<Greeting> {

    /**
     * What was disposed of, with the parameter the disposal function was handed.
     */
    public static final List<String> DISPOSED = new CopyOnWriteArrayList<>();

    @Override
    public void dispose(Greeting instance, Instance<Object> lookup, Parameters params) {
        DISPOSED.add(instance.text() + " / " + params.get("text", String.class));
    }
}
