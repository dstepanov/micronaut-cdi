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

import io.micronaut.cdi.internal.runtime.CdiBeanContainer;
import io.micronaut.context.ApplicationContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.Typed;
import jakarta.inject.Inject;
import jakarta.interceptor.InterceptorBinding;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Asking an {@code Instance} whether it is unsatisfied or ambiguous creates nothing, and each {@code get} invokes a
 * dependent producer once (CDI 4.1 section 5.6.1), also where Micronaut Core destroys the bean the lookup belongs to
 * as the product is created: an intercepted product takes the bean handed to its producer for its factory
 * (CONFORMANCE.md, Open points in Micronaut Core), which here is the application scoped bean holding the lookup.
 */
class InterceptedProducerLookupCountTest {

    static final List<String> EVENTS = new CopyOnWriteArrayList<>();

    /**
     * An interceptor binding no interceptor is bound to.
     */
    @InterceptorBinding
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD})
    public @interface Unbound {
    }

    /**
     * What the press produces.
     */
    public record Print(String by) {
    }

    /**
     * A bean of no bean type but Object, holding a lookup of prints.
     */
    @Dependent
    @Typed({})
    public static class Desk {
        @Inject
        Instance<Print> prints;

        public String id() {
            return "desk";
        }

        public Instance<Print> prints() {
            return prints;
        }
    }

    /**
     * The bean the press is handed for a desk.
     */
    @ApplicationScoped
    public static class Bureau extends Desk {
        @Override
        public String id() {
            return "bureau";
        }

        @jakarta.annotation.PreDestroy
        void destroyed() {
            EVENTS.add("bureau destroyed");
        }
    }

    /**
     * Produces prints, bound to no interceptor.
     */
    @Dependent
    @Unbound
    public static class Press {
        @Produces
        Print print(Desk desk) {
            EVENTS.add("produce");
            return new Print(desk.id());
        }
    }

    @Test
    void aLookupWhoseBeanIsDestroyedAsTheProductIsCreatedInvokesTheProducerOnce() {
        try (ApplicationContext context = ApplicationContext.run()) {
            Instance<Print> prints = context.getBean(CdiBeanContainer.class).createInstance().select(Bureau.class).get().prints();
            EVENTS.clear();
            assertEquals(false, prints.isUnsatisfied());
            assertEquals(false, prints.isAmbiguous());
            assertEquals(List.of(), EVENTS);
            // with Micronaut Core as it is, the creation destroys the bureau holding the lookup, which refuses the
            // product as it is done; once Core takes the declaring instance for the factory, the product is returned.
            // Either way the producer is invoked once: the lookup is not made again
            try {
                assertEquals("bureau", prints.get().by());
            } catch (IllegalStateException refused) {
                assertEquals(List.of("produce", "bureau destroyed"), EVENTS);
            }
            assertEquals(1, EVENTS.stream().filter("produce"::equals).count(), EVENTS.toString());
        }
    }

    @Disabled("Micronaut Core: markDependentAsFactory takes the parameter resolved first for the factory (CONFORMANCE.md, Open points in Core)")
    @Test
    void askingTheLookupCreatesNothingAndEachGetProducesOnce() {
        try (ApplicationContext context = ApplicationContext.run()) {
            Instance<Print> prints = context.getBean(CdiBeanContainer.class).createInstance().select(Bureau.class).get().prints();
            EVENTS.clear();
            List<String> seen = new ArrayList<>();
            seen.add("unsatisfied=" + prints.isUnsatisfied());
            seen.add("events=" + EVENTS.size());
            seen.add("ambiguous=" + prints.isAmbiguous());
            seen.add("events=" + EVENTS.size());
            seen.add("get=" + prints.get().by());
            seen.add("events=" + EVENTS.size());
            assertEquals(List.of("unsatisfied=false", "events=0", "ambiguous=false", "events=0", "get=bureau", "events=1"),
                seen);
        }
    }
}
