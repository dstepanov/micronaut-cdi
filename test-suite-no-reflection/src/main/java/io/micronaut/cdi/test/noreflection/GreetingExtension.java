package io.micronaut.cdi.test.noreflection;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.build.compatible.spi.BeanInfo;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.Messages;
import jakarta.enterprise.inject.build.compatible.spi.Registration;
import jakarta.enterprise.inject.build.compatible.spi.Synthesis;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticComponents;
import jakarta.enterprise.inject.build.compatible.spi.Validation;

import java.util.ArrayList;
import java.util.List;

/**
 * Describes a synthetic bean and a synthetic observer, is told about the bean in the registration phase, and
 * validates what it was told: every phase runs while the tests of this project compile, and the container the
 * tests start neither loads the extension nor runs it.
 */
public final class GreetingExtension implements BuildCompatibleExtension {

    /**
     * How many times the extension was instantiated in this JVM: once in the compiler's, and never in the one
     * the application runs in.
     */
    public static final java.util.concurrent.atomic.AtomicInteger INSTANTIATED =
        new java.util.concurrent.atomic.AtomicInteger();

    private final List<String> registered = new ArrayList<>();

    /**
     * Counts the instantiation.
     */
    public GreetingExtension() {
        INSTANTIATED.incrementAndGet();
    }

    /**
     * Adds the greeting and the observer of pings.
     *
     * @param components What the container is to have
     */
    @Synthesis
    public void synthesise(SyntheticComponents components) {
        components.addBean(Greeting.class)
            .type(Greeting.class)
            .scope(ApplicationScoped.class)
            .name("greeting")
            .withParam("text", "hello")
            .withParam("volume", 11)
            .withParam("tone", Greeting.Tone.WARM)
            .createWith(GreetingCreator.class)
            .disposeWith(GreetingDisposer.class);
        components.addObserver(Ping.class)
            .withParam("listener", "the synthetic observer")
            .observeWith(PingObserver.class);
    }

    /**
     * Hears about the greeting, which no class of the compilation declares.
     *
     * @param bean The bean
     */
    @Registration(types = Greeting.class)
    public void greetingIsRegistered(BeanInfo bean) {
        registered.add((bean.isSynthetic() ? "synthetic " : "") + bean.scope().name() + " " + bean.name());
    }

    /**
     * Fails the compilation unless the registration phase was told about the synthetic bean.
     *
     * @param messages What reports the problem
     */
    @Validation
    public void theSyntheticBeanWasRegistered(Messages messages) {
        if (!registered.equals(List.of("synthetic jakarta.enterprise.context.ApplicationScoped greeting"))) {
            messages.error("The registration phase should have been told about the synthetic greeting once, and "
                + "was told " + registered);
        }
    }
}
