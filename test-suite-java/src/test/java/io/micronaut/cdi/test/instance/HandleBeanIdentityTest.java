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
package io.micronaut.cdi.test.instance;

import io.micronaut.context.ApplicationContext;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.CDI;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The bean a handle reports is the bean of its definition the container answers with: equal to it, and to itself
 * on every call. Nothing is kept to make it the same object.
 */
class HandleBeanIdentityTest {

    @Dependent
    static class Lamp {
    }

    @Test
    void aHandleReportsTheBeanTheContainerAnswersWith() {
        try (ApplicationContext context = ApplicationContext.run()) {
            Bean<?> bean = CDI.current().getBeanContainer().getBeans(Lamp.class).iterator().next();
            Instance.Handle<Lamp> handle = CDI.current().select(Lamp.class).getHandle();
            assertEquals(bean, handle.getBean());
            assertEquals(bean.hashCode(), handle.getBean().hashCode());
            assertEquals(handle.getBean(), handle.getBean());
        }
    }
}
