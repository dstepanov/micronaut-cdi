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
package io.micronaut.cdi.test.bce;

import io.micronaut.context.ApplicationContext;
import jakarta.enterprise.inject.build.compatible.spi.BeanInfo;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.InvokerFactory;
import jakarta.enterprise.inject.build.compatible.spi.InvokerInfo;
import jakarta.enterprise.inject.build.compatible.spi.Parameters;
import jakarta.enterprise.inject.build.compatible.spi.Registration;
import jakarta.enterprise.inject.build.compatible.spi.Synthesis;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticComponents;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticObserver;
import jakarta.enterprise.inject.spi.EventContext;
import jakarta.enterprise.lang.model.declarations.MethodInfo;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An invoker an extension hands a synthetic observer names lookups whose resolution is a property of the deployment
 * (CDI 4.1, chapter 7): one with no bean to satisfy it fails the container as it starts, as it does for an invoker a
 * synthetic bean is handed, rather than at the first notification.
 */
class SyntheticObserverInvokerValidationTest {

    private static final String SOURCE = """
        package invoked;

        @jakarta.enterprise.context.Dependent
        public class Target {

            public void act(Missing missing) {
            }

            public interface Missing {
            }
        }
        """;

    @Test
    void anUnsatisfiedLookupOfAnObserverInvokerFailsTheStart() {
        RuntimeException failure = assertThrows(RuntimeException.class, () -> {
            try (ApplicationContext ignored = ExtensionDeployment.start(InvokingObserver.class, "invoked.Target",
                SOURCE)) {
                // the start alone has to fail
            }
        });
        assertTrue(messagesOf(failure).contains("has no bean to satisfy it"), messagesOf(failure));
    }

    private static String messagesOf(Throwable failure) {
        StringBuilder messages = new StringBuilder();
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            messages.append(cause.getMessage()).append('\n');
            if (cause.getCause() == cause) {
                break;
            }
        }
        return messages.toString();
    }

    /**
     * Builds an invoker of {@code Target.act} that looks its argument up, and hands it to a synthetic observer.
     */
    public static final class InvokingObserver implements BuildCompatibleExtension {

        private InvokerInfo invoker;

        @Registration(types = Object.class)
        public void register(BeanInfo bean, InvokerFactory invokers) {
            if (bean.isClassBean() && bean.declaringClass().name().equals("invoked.Target")) {
                MethodInfo act = bean.declaringClass().methods().stream()
                    .filter(method -> method.name().equals("act"))
                    .findFirst().orElseThrow();
                invoker = invokers.createInvoker(bean, act).withArgumentLookup(0).build();
            }
        }

        @Synthesis
        public void synthesise(SyntheticComponents components) {
            components.addObserver(Ping.class)
                .withParam("invoker", invoker)
                .observeWith(PingListener.class);
        }
    }

    /**
     * What the synthetic observer observes.
     *
     * @param text The text
     */
    public record Ping(String text) {
    }

    /**
     * The synthetic observer, which is never notified.
     */
    public static final class PingListener implements SyntheticObserver<Ping> {
        @Override
        public void observe(EventContext<Ping> event, Parameters params) {
        }
    }
}
