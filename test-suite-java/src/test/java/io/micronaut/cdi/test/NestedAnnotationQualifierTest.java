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
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.util.AnnotationLiteral;
import jakarta.inject.Qualifier;
import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A qualifier may carry a member that is itself an annotation. Selecting by a literal of such a qualifier
 * compares that member with the one the bean was compiled with, which is stored as metadata rather than as
 * a live annotation: the two have to read as the same value.
 */
class NestedAnnotationQualifierTest {

    /**
     * The member.
     */
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Region {
        String value();
    }

    /**
     * The qualifier carrying it.
     */
    @Qualifier
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER})
    public @interface Located {
        Region region();
    }

    /**
     * A literal of the member.
     */
    public static final class RegionLiteral extends AnnotationLiteral<Region> implements Region {
        private final String value;

        RegionLiteral(String value) {
            this.value = value;
        }

        @Override
        public String value() {
            return value;
        }
    }

    /**
     * A literal of the qualifier.
     */
    public static final class LocatedLiteral extends AnnotationLiteral<Located> implements Located {
        private final Region region;

        LocatedLiteral(Region region) {
            this.region = region;
        }

        @Override
        public Region region() {
            return region;
        }
    }

    /**
     * What is selected.
     */
    public interface Warehouse {
        String name();
    }

    /**
     * In the east.
     */
    @Dependent
    @Located(region = @Region("east"))
    public static class EastWarehouse implements Warehouse {
        @Override
        public String name() {
            return "east";
        }
    }

    /**
     * In the west.
     */
    @Dependent
    @Located(region = @Region("west"))
    public static class WestWarehouse implements Warehouse {
        @Override
        public String name() {
            return "west";
        }
    }

    @Test
    void aQualifierWithAnAnnotationMemberSelectsByThatMember() {
        try (ApplicationContext context = ApplicationContext.run()) {
            Instance<Object> lookup = context.getBean(CdiBeanContainer.class).createInstance();
            Instance<Warehouse> east = lookup.select(Warehouse.class, new LocatedLiteral(new RegionLiteral("east")));
            assertTrue(east.isResolvable(), "the east warehouse should be the one selected, but the lookup is "
                + (east.isUnsatisfied() ? "unsatisfied" : "ambiguous"));
            assertEquals("east", east.get().name());
            assertEquals("west",
                lookup.select(Warehouse.class, new LocatedLiteral(new RegionLiteral("west"))).get().name());
        }
    }
}
