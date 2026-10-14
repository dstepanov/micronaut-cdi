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

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.BeanInjectionProvider;
import io.micronaut.context.BeanResolutionContext;
import io.micronaut.context.Qualifier;
import io.micronaut.context.annotation.ResolveWith;
import io.micronaut.core.type.Argument;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * A point of a bean of the specification an application's own {@code ResolveWith} provider supplies is left to that
 * provider: the deployment is not refused for the type having no bean, and a parameter of an observer or a disposer
 * is supplied by the provider as a field is.
 */
class ApplicationInjectionProviderTest {

    public record Token(String value) {
    }

    @Singleton
    public static class TokenProvider implements BeanInjectionProvider {
        @SuppressWarnings("unchecked")
        @Override
        public <T> @Nullable T get(BeanResolutionContext resolutionContext, Argument<T> argument,
                                   @Nullable Qualifier<T> qualifier, boolean nullable) {
            return (T) new Token("supplied for " + argument.getName());
        }
    }

    public record Ping() {
    }

    public static class Widget {
    }

    @Dependent
    public static class Holder {
        static volatile @Nullable Token observed;
        static volatile @Nullable Token disposed;

        @Inject
        @ResolveWith(TokenProvider.class)
        Token token;

        void on(@Observes Ping ping, @ResolveWith(TokenProvider.class) Token token) {
            observed = token;
        }

        @Produces
        @Dependent
        Widget widget() {
            return new Widget();
        }

        void dispose(@Disposes Widget widget, @ResolveWith(TokenProvider.class) Token token) {
            disposed = token;
        }
    }

    @Dependent
    public static class Firer {
        @Inject
        Event<Ping> pings;
        @Inject
        Instance<Widget> widgets;
    }

    @Test
    void aPointAnApplicationsProviderSuppliesIsLeftToIt() {
        try (ApplicationContext context = ApplicationContext.run()) {
            assertEquals(new Token("supplied for token"), context.getBean(Holder.class).token);
            Firer firer = context.getBean(Firer.class);
            firer.pings.fire(new Ping());
            assertNotNull(Holder.observed);
            assertEquals("supplied for token", Holder.observed.value());
            Instance.Handle<Widget> handle = firer.widgets.getHandle();
            handle.get();
            handle.destroy();
            assertNotNull(Holder.disposed);
            assertEquals("supplied for token", Holder.disposed.value());
        }
    }
}
