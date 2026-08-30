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
package io.micronaut.cdi.test;

import io.micronaut.cdi.runtime.CdiBeanContainer;
import io.micronaut.cdi.test.extension.Flavor;
import io.micronaut.cdi.test.extension.Port;
import io.micronaut.cdi.test.extension.PortImpl;
import io.micronaut.cdi.test.extension.Signal;
import io.micronaut.cdi.test.extension.TicketCounter;
import io.micronaut.cdi.test.extension.Zest;
import io.micronaut.cdi.test.extension.Zesty;
import io.micronaut.context.ApplicationContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.context.control.RequestContextController;
import jakarta.enterprise.context.spi.AlterableContext;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.Bean;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A synthetic bean lives in the scope it was described with, an alternative is enabled by its priority, and a
 * bean is resolvable by exactly the types and qualifiers it declared.
 */
class SyntheticScopesAndAlternativesTest {

    @Test
    void aRequestScopedSyntheticBeanLivesAndDiesWithTheRequest() {
        try (ApplicationContext context = ApplicationContext.run()) {
            CdiBeanContainer container = context.getBean(CdiBeanContainer.class);
            Instance<TicketCounter> counters = container.createInstance().select(TicketCounter.class);
            // outside a request there is no context to hold the counter
            assertThrows(ContextNotActiveException.class, () -> counters.get().next());
            int disposedBefore = TicketCounter.DISPOSED.get();
            RequestContextController controller = context.getBean(RequestContextController.class);
            controller.activate();
            try {
                assertEquals(1, counters.get().next());
                // the same request holds the same counter
                assertEquals(2, counters.get().next());
            } finally {
                controller.deactivate();
            }
            // ending the request destroyed the counter through its disposal function
            assertEquals(disposedBefore + 1, TicketCounter.DISPOSED.get());
        }
    }

    @Test
    void anApplicationScopedSyntheticBeanIsReplacedWhenItsContextDestroysIt() {
        try (ApplicationContext context = ApplicationContext.run()) {
            CdiBeanContainer container = context.getBean(CdiBeanContainer.class);
            io.micronaut.cdi.test.extension.AuditLog first = container.createInstance()
                .select(io.micronaut.cdi.test.extension.AuditLog.class).get();
            Bean<?> bean = container.getBeans(io.micronaut.cdi.test.extension.AuditLog.class)
                .iterator().next();
            AlterableContext applicationContext =
                (AlterableContext) container.getContext(ApplicationScoped.class);
            applicationContext.destroy(bean);
            io.micronaut.cdi.test.extension.AuditLog second = container.createInstance()
                .select(io.micronaut.cdi.test.extension.AuditLog.class).get();
            assertNotSame(first, second);
        }
    }

    @Test
    void anAlternativeIsEnabledByItsPriorityAndOutranksTheOrdinaryBean() {
        try (ApplicationContext context = ApplicationContext.run()) {
            CdiBeanContainer container = context.getBean(CdiBeanContainer.class);
            // the selected alternative wins over the compiled RegularSignal
            assertEquals("selected-alternative",
                container.createInstance().select(Signal.class).get().source());
            // and the alternative no priority selected is not a bean of the deployment at all
            assertEquals(2, container.getBeans(Signal.class).size());
        }
    }

    @Test
    void aSyntheticBeanIsResolvableByExactlyTheTypesItDeclared() {
        try (ApplicationContext context = ApplicationContext.run()) {
            CdiBeanContainer container = context.getBean(CdiBeanContainer.class);
            assertEquals(8080, container.createInstance().select(Port.class).get().number());
            // the implementation class was not declared a bean type, so it resolves nothing
            assertTrue(container.createInstance().select(PortImpl.class).isUnsatisfied());
        }
    }

    @Test
    void aQualifierMemberTellsSyntheticBeansApartAndIsReportedAsWritten() {
        try (ApplicationContext context = ApplicationContext.run()) {
            CdiBeanContainer container = context.getBean(CdiBeanContainer.class);
            assertEquals("sweet", container.createInstance()
                .select(Zest.class, new Flavor.Literal("sweet")).get().flavour());
            assertEquals("sour", container.createInstance()
                .select(Zest.class, new Flavor.Literal("sour")).get().flavour());
            Set<Bean<?>> sweet = container.getBeans(Zest.class, new Flavor.Literal("sweet"));
            assertEquals(1, sweet.size());
            // the bean reports the member it was described with, not the annotation's defaults
            String reported = sweet.iterator().next().getQualifiers().stream()
                .filter(Flavor.class::isInstance)
                .map(qualifier -> ((Flavor) qualifier).value())
                .findFirst().orElse(null);
            assertEquals("sweet", reported);
        }
    }

    @Test
    void anAnnotationTheDiscoveryPhaseRegisteredIsAQualifier() {
        try (ApplicationContext context = ApplicationContext.run()) {
            CdiBeanContainer container = context.getBean(CdiBeanContainer.class);
            assertTrue(container.isQualifier(Zesty.class));
        }
    }

    @Test
    void aProducedNormalScopedInstanceIsDestroyedThroughItsContext() {
        try (ApplicationContext context = ApplicationContext.run()) {
            CdiBeanContainer container = context.getBean(CdiBeanContainer.class);
            WallClock proxy = container.createInstance().select(WallClock.class).get();
            int first = proxy.serial();
            Bean<?> bean = container.getBeans(WallClock.class).iterator().next();
            AlterableContext applicationContext =
                (AlterableContext) container.getContext(ApplicationScoped.class);
            // getBeanClass() of a produced bean is the producer's class; destruction must still find the
            // produced instance
            applicationContext.destroy(bean);
            assertTrue(proxy.serial() != first);
        }
    }

    /**
     * What the producer below makes: a normal scoped produced bean, each instance with its own serial.
     */
    public interface WallClock {

        /**
         * Which creation this instance came from.
         *
         * @return The serial
         */
        int serial();
    }

    /**
     * Produces the wall clock, so that the bean's class is this factory rather than the produced type.
     */
    @Dependent
    public static class WallClockFactory {

        private static final java.util.concurrent.atomic.AtomicInteger SERIALS =
            new java.util.concurrent.atomic.AtomicInteger();

        @jakarta.enterprise.inject.Produces
        @ApplicationScoped
        WallClock make() {
            int serial = SERIALS.incrementAndGet();
            return () -> serial;
        }
    }
}
