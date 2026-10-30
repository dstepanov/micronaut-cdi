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
package io.micronaut.cdi.test.context;

import io.micronaut.cdi.internal.context.RequestScope;
import io.micronaut.cdi.internal.runtime.CdiBeanContainer;
import io.micronaut.cdi.internal.runtime.CdiContext;
import io.micronaut.context.ApplicationContext;
import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.context.control.RequestContextController;
import jakarta.enterprise.context.spi.AlterableContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Asking a context what it holds, or to destroy what it holds none of, puts nothing into it: the store of the
 * contextuals a program handed the context is created by the first creation alone.
 */
class NonCreatingContextLookupTest {

    @Test
    void aLookupAndADestructionOfWhatIsNotHeldCreateNoStore() {
        try (ApplicationContext context = ApplicationContext.run()) {
            CdiBeanContainer container = context.getBean(CdiBeanContainer.class);
            RequestContextController requests = context.getBean(RequestContextController.class);
            List<String> log = new CopyOnWriteArrayList<>();
            Probe probe = Probe.of("absent", log);
            requests.activate();
            try {
                AlterableContext requestContext = (AlterableContext) container.getContext(RequestScoped.class);
                assertNull(requestContext.get(probe));
                requestContext.destroy(probe);
                assertFalse(context.getBean(RequestScope.class).remove(CdiContext.CONTEXTUAL_STORE_ID).isPresent(),
                    "the request holds no store of contextuals, none having been created");
            } finally {
                requests.deactivate();
            }
        }
    }
}
