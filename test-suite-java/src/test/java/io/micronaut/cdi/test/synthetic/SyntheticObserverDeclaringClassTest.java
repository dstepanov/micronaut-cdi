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
package io.micronaut.cdi.test.synthetic;

import io.micronaut.cdi.internal.runtime.CdiBeanContainer;
import io.micronaut.cdi.test.extension.Dispatch;
import io.micronaut.cdi.test.extension.DispatchDesk;
import io.micronaut.cdi.test.extension.ReviewScenariosExtension;
import io.micronaut.context.ApplicationContext;
import jakarta.enterprise.inject.spi.ObserverMethod;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A synthetic observer is reported as declared by the class its extension named with {@code declaringClass}, and by
 * the extension where it named none (section 2.10.5) - the class the registration phase reported it with, not the
 * class that observes.
 */
class SyntheticObserverDeclaringClassTest {

    @Test
    void theObserverIsDeclaredByTheClassItsExtensionNamed() {
        try (ApplicationContext context = ApplicationContext.run()) {
            CdiBeanContainer container = context.getBean(CdiBeanContainer.class);
            Set<Class<?>> declaredBy = container.resolveObserverMethods(new Dispatch("parcel")).stream()
                .filter(observer -> observer.getObservedType() == Dispatch.class)
                .map(ObserverMethod::getBeanClass)
                .collect(Collectors.toSet());
            assertEquals(Set.of(DispatchDesk.class, ReviewScenariosExtension.class), declaredBy);
        }
    }
}
