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

import io.micronaut.cdi.runtime.CdiBeanContainer;
import io.micronaut.context.ApplicationContext;
import jakarta.enterprise.inject.Default;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A bean written with the singleton pseudo-scope and nothing else is a bean of the specification.
 *
 * <p>Section 2.5.1 counts {@code @Singleton} among the bean defining annotations, so a class that declares it is a
 * bean; section 2.4.3 gives a bean that declares no qualifier the default one. Both halves were being skipped for
 * that one scope, and a class written with {@code @Singleton} alone did not resolve at an injection point, or at a
 * programmatic lookup, that named no qualifier at all.</p>
 */
class SingletonPseudoScopeBeanTest {

    @Test
    void aSingletonBeanCarriesTheDefaultQualifier() {
        try (ApplicationContext context = ApplicationContext.run()) {
            CdiBeanContainer container = context.getBean(CdiBeanContainer.class);
            Set<Bean<?>> beans = container.getBeans(Lighthouse.class);
            assertEquals(1, beans.size());
            Set<Annotation> qualifiers = beans.iterator().next().getQualifiers();
            assertTrue(qualifiers.stream().anyMatch(qualifier -> qualifier instanceof Default),
                () -> "the default qualifier is not among " + qualifiers);
        }
    }

    @Test
    void aLookupThatNamesNoQualifierResolvesASingletonBean() {
        try (ApplicationContext context = ApplicationContext.run()) {
            CdiBeanContainer container = context.getBean(CdiBeanContainer.class);
            assertEquals("lit", container.createInstance().select(Lighthouse.class).get().shine());
        }
    }

    @Test
    void anInjectionPointThatNamesNoQualifierResolvesASingletonBean() {
        try (ApplicationContext context = ApplicationContext.run()) {
            CdiBeanContainer container = context.getBean(CdiBeanContainer.class);
            Keeper keeper = container.createInstance().select(Keeper.class).get();
            assertNotNull(keeper.lighthouse);
            assertEquals("lit", keeper.lighthouse.shine());
        }
    }

    /**
     * A bean whose only annotation is the singleton pseudo-scope.
     */
    @Singleton
    public static class Lighthouse {

        public String shine() {
            return "lit";
        }
    }

    /**
     * Asks for it without naming a qualifier.
     */
    @Singleton
    public static class Keeper {

        @Inject
        Lighthouse lighthouse;
    }
}
