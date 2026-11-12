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

import io.micronaut.cdi.internal.runtime.CdiBeanContainer;
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
import jakarta.enterprise.invoke.Invoker;
import jakarta.enterprise.lang.model.declarations.MethodInfo;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * An invoker of a static method of a bean that narrowed its bean types with {@code Typed} - so that it is not a
 * bean of its own class - invokes the method: the bean is found though a lookup of its class does not find it.
 */
class TypedBeanInvokerTest {

    private static final String SOURCE = """
        package typed;

        @jakarta.enterprise.context.Dependent
        @jakarta.enterprise.inject.Typed(Object.class)
        public class Target {

            public static String shout(String text) {
                return text.toUpperCase();
            }
        }
        """;

    @Test
    void anInvokerOfAStaticMethodOfATypedBeanInvokesIt() {
        Shouting.result = null;
        try (ApplicationContext context = ExtensionDeployment.start(InvokingObserver.class, "typed.Target", SOURCE)) {
            context.getBean(CdiBeanContainer.class).getEvent().select(Ping.class).fire(new Ping("quiet"));
            assertEquals("QUIET", Shouting.result);
        }
    }

    /**
     * Builds an invoker of {@code Target.shout}, and hands it to a synthetic observer.
     */
    public static final class InvokingObserver implements BuildCompatibleExtension {

        private InvokerInfo invoker;

        @Registration(types = Object.class)
        public void register(BeanInfo bean, InvokerFactory invokers) {
            if (bean.isClassBean() && bean.declaringClass().name().equals("typed.Target")) {
                MethodInfo shout = bean.declaringClass().methods().stream()
                    .filter(method -> method.name().equals("shout"))
                    .findFirst().orElseThrow();
                invoker = invokers.createInvoker(bean, shout).build();
            }
        }

        @Synthesis
        public void synthesise(SyntheticComponents components) {
            components.addObserver(Ping.class)
                .withParam("invoker", invoker)
                .observeWith(Shouting.class);
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
     * The synthetic observer, which invokes the static method with the text of the event.
     */
    public static final class Shouting implements SyntheticObserver<Ping> {

        static volatile Object result;

        @Override
        @SuppressWarnings("unchecked")
        public void observe(EventContext<Ping> event, Parameters params) throws Exception {
            Invoker<Object, Object> invoker = params.get("invoker", Invoker.class);
            result = invoker.invoke(null, new Object[] {event.getEvent().text()});
        }
    }
}
