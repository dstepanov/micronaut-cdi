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
import jakarta.enterprise.inject.build.compatible.spi.Registration;
import jakarta.enterprise.lang.model.declarations.MethodInfo;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every invoker an extension builds is validated (CDI 4.1 section 10.3.1): one whose lookup has no bean to satisfy
 * it fails the container as it starts though the extension handed it to nothing.
 */
class DiscardedInvokerValidationTest {

    private static final String SOURCE = """
        package discarded;

        @jakarta.enterprise.context.Dependent
        public class Target {

            public void act(Missing missing) {
            }

            public interface Missing {
            }
        }
        """;

    @Test
    void anUnsatisfiedLookupOfAnInvokerHandedToNothingFailsTheStart() {
        RuntimeException failure = assertThrows(RuntimeException.class, () -> {
            try (ApplicationContext ignored = ExtensionDeployment.start(Discarding.class, "discarded.Target",
                SOURCE)) {
                // the start alone has to fail
            }
        });
        StringBuilder messages = new StringBuilder();
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            messages.append(cause.getMessage()).append('\n');
        }
        assertTrue(messages.toString().contains("has no bean to satisfy it"), messages.toString());
    }

    /**
     * Builds an invoker of {@code Target.act} that looks its argument up, and keeps it to itself.
     */
    public static final class Discarding implements BuildCompatibleExtension {

        @Registration(types = Object.class)
        public void register(BeanInfo bean, InvokerFactory invokers) {
            if (bean.isClassBean() && bean.declaringClass().name().equals("discarded.Target")) {
                MethodInfo act = bean.declaringClass().methods().stream()
                    .filter(method -> method.name().equals("act"))
                    .findFirst().orElseThrow();
                invokers.createInvoker(bean, act).withArgumentLookup(0).build();
            }
        }
    }
}
