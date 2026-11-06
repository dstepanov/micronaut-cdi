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
package io.micronaut.cdi.test.bce;

import io.micronaut.context.ApplicationContext;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.Discovery;
import jakarta.enterprise.inject.build.compatible.spi.MetaAnnotations;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.enterprise.util.AnnotationLiteral;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A qualifier a build compatible extension registered, which no bean class carries and only an observer method's
 * parameter does, has the members that observer was compiled with: the record of the registration names the
 * annotation alone, and is no evidence that it has none. A literal of the qualifier fired with an event selects the
 * observer by the value it was written with.
 */
public class ExtensionQualifierMembersTest {

    private static final String SOURCE = """
        package painted;

        import io.micronaut.cdi.test.bce.ExtensionQualifierMembersTest.Hue;
        import io.micronaut.cdi.test.bce.ExtensionQualifierMembersTest.Paint;

        @jakarta.enterprise.context.ApplicationScoped
        public class Easel {

            public void red(@jakarta.enterprise.event.Observes @Hue("red") Paint paint) {
                paint.seen().add("red");
            }
        }
        """;

    @Test
    void aLiteralOfARegisteredQualifierSelectsTheObserverByItsMembers() {
        try (ApplicationContext context = ExtensionDeployment.start(HueQualifier.class, "painted.Easel", SOURCE)) {
            BeanManager manager = context.getBean(BeanManager.class);

            Paint red = new Paint(new ArrayList<>());
            manager.getEvent().select(Paint.class, new Hue.Literal("red")).fire(red);
            assertEquals(List.of("red"), red.seen());

            Paint blue = new Paint(new ArrayList<>());
            manager.getEvent().select(Paint.class, new Hue.Literal("blue")).fire(blue);
            assertEquals(List.of(), blue.seen());
        }
    }

    /**
     * Makes {@link Hue} a qualifier, which nothing on the annotation says it is.
     */
    public static final class HueQualifier implements BuildCompatibleExtension {
        @Discovery
        public void hue(MetaAnnotations meta) {
            meta.addQualifier(Hue.class);
        }
    }

    /**
     * The event, which carries what observed it.
     *
     * @param seen The observers that saw it
     */
    public record Paint(List<String> seen) {
    }

    /**
     * An annotation with a binding member, a qualifier only by the extension's registration.
     */
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Hue {

        String value();

        /**
         * A literal of the annotation.
         */
        final class Literal extends AnnotationLiteral<Hue> implements Hue {

            private final String value;

            Literal(String value) {
                this.value = value;
            }

            @Override
            public String value() {
                return value;
            }
        }
    }
}
