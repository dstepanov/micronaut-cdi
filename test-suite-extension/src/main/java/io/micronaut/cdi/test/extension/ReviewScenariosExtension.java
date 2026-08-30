/*
 * Copyright 2017-2026 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.cdi.test.extension;

import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.Discovery;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.build.compatible.spi.MetaAnnotations;
import jakarta.enterprise.inject.build.compatible.spi.Parameters;
import jakarta.enterprise.inject.build.compatible.spi.Synthesis;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticBeanCreator;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticBeanDisposer;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticComponents;

/**
 * The synthetic beans the review scenarios read back: a request scoped one, a selected and an unselected
 * alternative, one resolvable only by its declared type, and two told apart by a qualifier's member.
 */
public final class ReviewScenariosExtension implements BuildCompatibleExtension {

    /**
     * Makes {@link Zesty} a qualifier, though nothing on the annotation says so.
     *
     * @param meta What the discovery phase may register
     */
    @Discovery
    public void zestyIsAQualifier(MetaAnnotations meta) {
        meta.addQualifier(Zesty.class);
    }

    /**
     * Describes the synthetic beans.
     *
     * @param components What the extension adds to the container
     */
    @Synthesis
    public void reviewScenarioBeans(SyntheticComponents components) {
        components.addBean(TicketCounter.class)
            .type(TicketCounter.class)
            .scope(RequestScoped.class)
            .createWith(TicketCounterCreator.class)
            .disposeWith(TicketCounterDisposer.class);
        components.addBean(Signal.class)
            .type(Signal.class)
            .alternative(true)
            .priority(100)
            .withParam("source", "selected-alternative")
            .createWith(SignalCreator.class);
        components.addBean(Signal.class)
            .type(Signal.class)
            .alternative(true)
            .withParam("source", "unselected-alternative")
            .createWith(SignalCreator.class);
        components.addBean(PortImpl.class)
            .type(Port.class)
            .createWith(PortCreator.class);
        components.addBean(Zest.class)
            .type(Zest.class)
            .qualifier(new Flavor.Literal("sweet"))
            .withParam("flavour", "sweet")
            .createWith(ZestCreator.class);
        components.addBean(Zest.class)
            .type(Zest.class)
            .qualifier(new Flavor.Literal("sour"))
            .withParam("flavour", "sour")
            .createWith(ZestCreator.class);
    }

    /**
     * Creates a ticket counter.
     */
    public static final class TicketCounterCreator implements SyntheticBeanCreator<TicketCounter> {
        @Override
        public TicketCounter create(Instance<Object> lookup, Parameters params) {
            return new TicketCounter();
        }
    }

    /**
     * Counts the counters a request destroyed with it.
     */
    public static final class TicketCounterDisposer implements SyntheticBeanDisposer<TicketCounter> {
        @Override
        public void dispose(TicketCounter instance, Instance<Object> lookup, Parameters params) {
            TicketCounter.DISPOSED.incrementAndGet();
        }
    }

    /**
     * Creates a signal that says which description it came from.
     */
    public static final class SignalCreator implements SyntheticBeanCreator<Signal> {
        @Override
        public Signal create(Instance<Object> lookup, Parameters params) {
            String source = params.get("source", String.class);
            return () -> source;
        }
    }

    /**
     * Creates the port implementation.
     */
    public static final class PortCreator implements SyntheticBeanCreator<PortImpl> {
        @Override
        public PortImpl create(Instance<Object> lookup, Parameters params) {
            return new PortImpl();
        }
    }

    /**
     * Creates a zest of the flavour the description named.
     */
    public static final class ZestCreator implements SyntheticBeanCreator<Zest> {
        @Override
        public Zest create(Instance<Object> lookup, Parameters params) {
            return new Zest(params.get("flavour", String.class));
        }
    }
}
