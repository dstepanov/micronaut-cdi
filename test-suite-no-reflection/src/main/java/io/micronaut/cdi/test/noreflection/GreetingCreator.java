package io.micronaut.cdi.test.noreflection;

import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.build.compatible.spi.Parameters;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticBeanCreator;

/**
 * Creates the greeting from the parameters the extension attached while the application compiled.
 */
public final class GreetingCreator implements SyntheticBeanCreator<Greeting> {

    @Override
    public Greeting create(Instance<Object> lookup, Parameters params) {
        return new Greeting(params.get("text", String.class), params.get("volume", Integer.class),
            params.get("tone", Greeting.Tone.class));
    }
}
