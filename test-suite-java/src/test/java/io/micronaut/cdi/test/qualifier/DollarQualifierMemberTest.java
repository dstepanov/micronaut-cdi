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
import io.micronaut.context.ApplicationContext;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.spi.InjectionPoint;
import jakarta.enterprise.util.AnnotationLiteral;
import jakarta.inject.Inject;
import jakarta.inject.Qualifier;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Java allows a member of an annotation interface to be named with a leading dollar sign. Such a member of a
 * qualifier is a binding member like any other: only the names Micronaut keeps for itself in the values of an
 * annotation ({@code $nonBinding}, {@code $stereotypes}) are not members of it. A literal selects by it, and the
 * qualifiers an injection point reports carry it.
 */
class DollarQualifierMemberTest {

    @Qualifier
    @Retention(RetentionPolicy.RUNTIME)
    @interface Gauged {
        String $level();
    }

    static final class GaugedLiteral extends AnnotationLiteral<Gauged> implements Gauged {

        private final String level;

        GaugedLiteral(String level) {
            this.level = level;
        }

        @Override
        public String $level() {
            return level;
        }
    }

    interface Gauge {
    }

    @Gauged($level = "high")
    @Dependent
    static class High implements Gauge {
        @Inject
        InjectionPoint injectionPoint;
    }

    @Gauged($level = "low")
    @Dependent
    static class Low implements Gauge {
    }

    @Dependent
    static class Panel {
        @Inject
        @Gauged($level = "high")
        Gauge gauge;
    }

    @Test
    void aLiteralSelectsByAMemberNamedWithADollarSign() {
        try (ApplicationContext context = ApplicationContext.run()) {
            MicronautBeanContainer container = context.getBean(MicronautBeanContainer.class);
            assertEquals(List.of(High.class), container.createInstance().select(Gauge.class,
                new GaugedLiteral("high")).stream().map(Object::getClass).toList());
            assertEquals(List.of(Low.class), container.createInstance().select(Gauge.class,
                new GaugedLiteral("low")).stream().map(Object::getClass).toList());
        }
    }

    @Test
    void anInjectionPointReportsAMemberNamedWithADollarSign() {
        try (ApplicationContext context = ApplicationContext.run()) {
            High high = (High) context.getBean(Panel.class).gauge;
            Gauged reported = null;
            for (Annotation qualifier : high.injectionPoint.getQualifiers()) {
                if (qualifier instanceof Gauged gauged) {
                    reported = gauged;
                }
            }
            assertEquals("high", reported == null ? null : reported.$level());
        }
    }
}
