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
import io.micronaut.cdi.test.extension.Ledger;
import io.micronaut.cdi.test.extension.Tier;
import io.micronaut.context.ApplicationContext;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A synthetic bean is qualified by what its qualifier's members say, the way a compiled bean is: an enum member
 * read off the literal an extension gave has to match the same literal at a lookup, and a name given beside a
 * qualifier is kept alongside it rather than in its place.
 */
class SyntheticEnumQualifierTest {

    @Test
    void aSyntheticBeanIsSelectedByTheEnumMemberOfItsQualifier() {
        try (ApplicationContext context = ApplicationContext.run()) {
            CdiBeanContainer container = context.getBean(CdiBeanContainer.class);
            Instance<Object> lookup = container.createInstance();
            Instance<Ledger> gold = lookup.select(Ledger.class, new Tier.Literal(Tier.Level.GOLD));
            assertTrue(gold.isResolvable(), "the gold ledger should be selected by its qualifier's member, "
                + "but the lookup is " + (gold.isUnsatisfied() ? "unsatisfied" : "ambiguous"));
            assertEquals("gold", gold.get().tier());
            assertEquals("silver",
                lookup.select(Ledger.class, new Tier.Literal(Tier.Level.SILVER)).get().tier());
        }
    }

    @Test
    void aNameGivenBesideAQualifierIsKeptBesideIt() {
        try (ApplicationContext context = ApplicationContext.run()) {
            CdiBeanContainer container = context.getBean(CdiBeanContainer.class);
            assertEquals(1, container.getBeans("silver-ledger").size(),
                "the silver ledger should be found by the name it was given");
            Instance<Ledger> silver = container.createInstance()
                .select(Ledger.class, new Tier.Literal(Tier.Level.SILVER));
            assertTrue(silver.isResolvable(), "the named ledger should still be selected by its qualifier, "
                + "but the lookup is " + (silver.isUnsatisfied() ? "unsatisfied" : "ambiguous"));
        }
    }
}
