package io.micronaut.cdi.test.extension.signpost;

import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.build.compatible.spi.BeanInfo;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.Messages;
import jakarta.enterprise.inject.build.compatible.spi.Parameters;
import jakarta.enterprise.inject.build.compatible.spi.Registration;
import jakarta.enterprise.inject.build.compatible.spi.Synthesis;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticBeanCreator;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticComponents;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticObserver;
import jakarta.enterprise.inject.build.compatible.spi.Validation;
import jakarta.enterprise.inject.spi.EventContext;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Describes a synthetic bean and a synthetic observer, is told about the bean in the registration phase, and
 * validates what it was told. Every phase runs in the compiler of whatever language the application is written
 * in, and the compilation fails unless registration heard about the synthetic bean exactly once.
 */
public final class SignpostExtension implements BuildCompatibleExtension {

    private final List<String> registered = new ArrayList<>();

    /**
     * Adds the signpost and the observer of arrivals.
     *
     * @param components What the container is to have
     */
    @Synthesis
    public void synthesise(SyntheticComponents components) {
        components.addBean(Signpost.class)
            .type(Signpost.class)
            .name("signpost")
            .withParam("text", "this way")
            .createWith(SignpostCreator.class);
        components.addObserver(Arrival.class)
            .withParam("greeting", "welcome")
            .observeWith(ArrivalObserver.class);
    }

    /**
     * Hears about the signpost, which no class of the compilation declares.
     *
     * @param bean The bean
     */
    @Registration(types = Signpost.class)
    public void signpostIsRegistered(BeanInfo bean) {
        registered.add((bean.isSynthetic() ? "synthetic " : "") + bean.name());
    }

    /**
     * Fails the compilation unless the registration phase was told about the synthetic bean.
     *
     * @param messages What reports the problem
     */
    @Validation
    public void theSyntheticBeanWasRegistered(Messages messages) {
        if (!registered.equals(List.of("synthetic signpost"))) {
            messages.error("The registration phase should have been told about the synthetic signpost once, and "
                + "was told " + registered);
        }
    }

    /**
     * Creates the signpost from the parameter the extension attached.
     */
    public static final class SignpostCreator implements SyntheticBeanCreator<Signpost> {

        @Override
        public Signpost create(Instance<Object> lookup, Parameters params) {
            return new Signpost(params.get("text", String.class));
        }
    }

    /**
     * Records the arrivals the synthetic observer was notified of.
     */
    public static final class ArrivalObserver implements SyntheticObserver<Arrival> {

        /**
         * What was observed, with the parameter the extension attached to the observer.
         */
        public static final List<String> OBSERVED = new CopyOnWriteArrayList<>();

        @Override
        public void observe(EventContext<Arrival> event, Parameters params) {
            OBSERVED.add(params.get("greeting", String.class) + " " + event.getEvent().traveller());
        }
    }
}
