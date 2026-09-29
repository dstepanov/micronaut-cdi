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
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.spi.InjectionPoint;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * The injection point of a parameter of a producer method names the producer method.
 *
 * <p>Micronaut resolves the parameters of a factory method as the arguments of the constructor of the bean it
 * produces, which is a method all the same; the specification's member is that method.</p>
 */
class ProducerParameterInjectionPointTest {

    @Test
    void aProducerParameterInjectionPointNamesTheProducerMethod() {
        try (ApplicationContext context = ApplicationContext.run()) {
            CdiBeanContainer container = context.getBean(CdiBeanContainer.class);
            Label label = container.createInstance().select(Label.class).get();
            Method member = assertInstanceOf(Method.class, label.tag().at().getMember());
            assertEquals("label", member.getName());
            assertEquals(Labeller.class, member.getDeclaringClass());
        }
    }

    /**
     * What is produced for an injection point, carrying the point it was produced for.
     *
     * @param at The injection point
     */
    public record Tag(InjectionPoint at) {
    }

    /**
     * What a producer method makes of the tag it is injected with.
     *
     * @param tag The tag
     */
    public record Label(Tag tag) {
    }

    /**
     * Produces a tag for whichever point asked for one.
     */
    @Singleton
    public static class TagMaker {

        /**
         * @param at The point being injected into
         * @return The tag for it
         */
        @Produces
        @Dependent
        Tag tag(InjectionPoint at) {
            return new Tag(at);
        }
    }

    /**
     * Asks for a tag in a parameter of a producer method.
     */
    @Singleton
    public static class Labeller {

        /**
         * @param tag The tag
         * @return The label
         */
        @Produces
        @Dependent
        Label label(Tag tag) {
            return new Label(tag);
        }
    }
}
