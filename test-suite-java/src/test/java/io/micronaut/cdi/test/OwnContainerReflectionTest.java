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
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.enterprise.inject.spi.InjectionPoint;
import jakarta.enterprise.util.TypeLiteral;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An injection point answers its member, and a container compares types it was not compiled with, through the
 * module that reads classes of the container they belong to, not through whichever container started last: two
 * containers can run side by side, and only one of them may have the module.
 */
class OwnContainerReflectionTest {

    private static final String REFLECTION_MODULE = "io.micronaut.cdi.reflection.ReflectiveCdi";

    /**
     * Told the injection point it is injected into.
     */
    @Dependent
    public static class Probe {

        @Inject
        InjectionPoint at;
    }

    @Dependent
    public static class Holder {

        @Inject
        Probe probe;
    }

    @Test
    void eachContainerAnswersWithItsOwnReflectionModule() {
        try (ApplicationContext withModule = ApplicationContext.run()) {
            // started second, so the most recent container is the one without the module
            try (ApplicationContext withoutModule = ApplicationContext.builder()
                .beansPredicate(bean -> !bean.getBeanType().getName().equals(REFLECTION_MODULE))
                .start()) {

                InjectionPoint answered = withModule.getBean(Holder.class).probe.at;
                Field member = assertInstanceOf(Field.class, answered.getMember());
                assertEquals("probe", member.getName());

                InjectionPoint unanswered = withoutModule.getBean(Holder.class).probe.at;
                assertThrows(UnsupportedOperationException.class, unanswered::getMember,
                    "the container without the module does not borrow the other one's");
            }
        }
    }

    @Test
    void eachContainerMatchesTypesItWasNotCompiledWithByItsOwnModule() {
        // ArrayList was not compiled with the processor: only the module that reads classes knows it is a List
        java.lang.reflect.Type event = new TypeLiteral<java.util.ArrayList<String>>() { }.getType();
        java.lang.reflect.Type observed = new TypeLiteral<java.util.List<String>>() { }.getType();
        try (ApplicationContext withModule = ApplicationContext.run()) {
            try (ApplicationContext withoutModule = ApplicationContext.builder()
                .beansPredicate(bean -> !bean.getBeanType().getName().equals(REFLECTION_MODULE))
                .start()) {

                assertTrue(withModule.getBean(BeanManager.class)
                    .isMatchingEvent(event, Set.of(), observed, Set.of()));
                assertFalse(withoutModule.getBean(BeanManager.class)
                    .isMatchingEvent(event, Set.of(), observed, Set.of()),
                    "the container without the module knows nothing above a class it was not compiled with");
            }
        }
    }
}
