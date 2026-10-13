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
package io.micronaut.cdi.test.qualifier;

import io.micronaut.cdi.MicronautBeanContainer;
import io.micronaut.cdi.MicronautInstance;
import io.micronaut.context.ApplicationContext;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.type.Argument;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.inject.Qualifier;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A qualifier a program builds as an {@code AnnotationValue} holds its members as the program gave them - an enum
 * constant, a class, a nested annotation of its own - where the one compiled onto a bean holds them as Micronaut
 * records them. The two are compared as the same values: an enum constant as its name, and a nested annotation
 * member by member, every member of it, a member one side leaves out at its default included.
 */
class QualifierMemberValuesTest {

    enum Level {
        GOLD, SILVER
    }

    @Qualifier
    @Retention(RetentionPolicy.RUNTIME)
    @interface Tier {
        Level level();
    }

    @Retention(RetentionPolicy.RUNTIME)
    @interface Region {
        String a() default "";

        String b() default "";
    }

    @Qualifier
    @Retention(RetentionPolicy.RUNTIME)
    @interface Area {
        Region region();
    }

    interface Account {
    }

    @Tier(level = Level.GOLD)
    @Dependent
    static class GoldAccount implements Account {
    }

    @Tier(level = Level.SILVER)
    @Dependent
    static class SilverAccount implements Account {
    }

    interface Depot {
    }

    @Area(region = @Region(a = "east"))
    @Dependent
    static class EastDepot implements Depot {
    }

    private static AnnotationValue<Area> area(String member, String value) {
        return AnnotationValue.builder(Area.class)
            .member("region", AnnotationValue.builder(Region.class).member(member, value).build())
            .build();
    }

    @Test
    void anEnumConstantMatchesTheNameItWasCompiledAs() {
        try (ApplicationContext context = ApplicationContext.run()) {
            MicronautBeanContainer container = (MicronautBeanContainer) CDI.current().getBeanContainer();
            MicronautInstance<Account> accounts = container.createInstance().select(Account.class, Any.Literal.INSTANCE);
            AnnotationValue<Tier> gold = AnnotationValue.builder(Tier.class).member("level", Level.GOLD).build();
            MicronautInstance<Account> selected = accounts.select(gold);
            assertTrue(selected.isResolvable(), "unsatisfied or ambiguous");
            assertEquals(GoldAccount.class, selected.get().getClass());
            assertEquals(1, container.getBeans(Argument.of(Account.class), gold).size());
        }
    }

    @Test
    void aNestedAnnotationIsComparedByEveryMember() {
        try (ApplicationContext context = ApplicationContext.run()) {
            MicronautBeanContainer container = (MicronautBeanContainer) CDI.current().getBeanContainer();
            MicronautInstance<Depot> depots = container.createInstance().select(Depot.class, Any.Literal.INSTANCE);
            assertEquals(EastDepot.class, depots.select(area("a", "east")).get().getClass());
            assertTrue(depots.select(area("b", "west")).isUnsatisfied(),
                "@Region(b = \"west\") is not @Region(a = \"east\")");
            assertTrue(container.getBeans(Argument.of(Depot.class), area("b", "west")).isEmpty());
        }
    }
}
