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
package io.micronaut.cdi.internal.runtime;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Executable;
import jakarta.enterprise.context.Dependent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * What an invoker invokes is found once for every invoker of the same method, and kept by the container rather than
 * by each invoker: building the same invoker over and over neither finds it again nor grows what is kept.
 */
class InvokerTargetsTest {

    /**
     * The bean whose method is invoked.
     */
    @Dependent
    public static class Greeter {
        @Executable
        public String greet(String name) {
            return "hello " + name;
        }
    }

    @Test
    void theInvokersOfOneMethodShareOneTarget() throws Exception {
        try (ApplicationContext context = ApplicationContext.run()) {
            InvokerTargets targets = context.getBean(InvokerTargets.class);
            int before = targets.size();
            RecordedInvoker.Target first = null;
            for (int i = 0; i < 100; i++) {
                RecordedInvoker invoker = new RecordedInvoker(context, Greeter.class.getName(), Greeter.class,
                    "greet", new String[] {String.class.getName()}, false, false, new boolean[] {false});
                assertEquals("hello " + i, invoker.invoke(new Greeter(), new Object[] {String.valueOf(i)}));
                if (first == null) {
                    first = invoker.target();
                } else {
                    assertSame(first, invoker.target(), "found once, and shared");
                }
            }
            assertEquals(before + 1, targets.size(), "one entry for the one method, however many invokers");
        }
    }
}
