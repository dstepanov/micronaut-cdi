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

import io.micronaut.cdi.internal.runtime.CdiBeanContainer;
import io.micronaut.context.ApplicationContext;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.interceptor.InterceptorBinding;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The dependent instance a producer method is invoked on is destroyed once the call completes (CDI 4.1 section
 * 6.4.2), and a dependent instance injected into a parameter of the producer method when its product is destroyed
 * (section 6.4.1), also where the declaring bean carries an interceptor binding.
 *
 * <p>A class-level binding makes Micronaut build the product's definition as an intercepted one, which resolves the
 * parameters of the producer before the declaring instance; Micronaut Core then takes the first dependent it resolved
 * for the declaring instance (CONFORMANCE.md, Open points in Core).</p>
 */
class UnboundInterceptorBindingDeclaringBeanTest {

    static final List<String> EVENTS = new CopyOnWriteArrayList<>();

    /**
     * An interceptor binding no interceptor is bound to.
     */
    @InterceptorBinding
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD})
    public @interface Unbound {
    }

    /**
     * What the folder produces.
     */
    public record Sheet(String id) {
    }

    /**
     * What the drawer produces.
     */
    public record Card(String id) {
    }

    /**
     * What the shelf produces.
     */
    public record Label(String id) {
    }

    /**
     * Handed to the producers.
     */
    @Dependent
    public static class Pen {
        @PreDestroy
        void destroyed() {
            EVENTS.add("pen destroyed");
        }
    }

    /**
     * Produces sheets with a pen, bound to no interceptor.
     */
    @Dependent
    @Unbound
    public static class Folder {
        @Produces
        Sheet sheet(Pen pen) {
            EVENTS.add("produce");
            return new Sheet("sheet");
        }

        void dispose(@Disposes Sheet sheet) {
            EVENTS.add("dispose");
        }

        @PreDestroy
        void destroyed() {
            EVENTS.add("folder destroyed");
        }
    }

    /**
     * Produces cards with nothing handed to it, bound to no interceptor.
     */
    @Dependent
    @Unbound
    public static class Drawer {
        @Produces
        Card card() {
            EVENTS.add("produce");
            return new Card("card");
        }

        void dispose(@Disposes Card card) {
            EVENTS.add("dispose");
        }

        @PreDestroy
        void destroyed() {
            EVENTS.add("drawer destroyed");
        }
    }

    /**
     * Produces labels with a pen, bound to no interceptor, and lives in the application scope.
     */
    @ApplicationScoped
    @Unbound
    public static class Shelf {
        @Produces
        Label label(Pen pen) {
            EVENTS.add("produce");
            return new Label("label");
        }
    }

    @Test
    void theDeclaringInstanceOfAProducerWithNoParameterIsDestroyedOnceItReturns() {
        try (ApplicationContext context = ApplicationContext.run()) {
            Instance<Card> cards = context.getBean(CdiBeanContainer.class).createInstance().select(Card.class);
            EVENTS.clear();
            Card card = cards.get();
            assertEquals(List.of("produce", "drawer destroyed"), EVENTS);
            EVENTS.clear();
            cards.destroy(card);
            assertEquals(List.of("dispose", "drawer destroyed"), EVENTS);
        }
    }

    @Disabled("Micronaut Core: markDependentAsFactory takes the parameter resolved first for the factory (CONFORMANCE.md, Open points in Core)")
    @Test
    void theDeclaringInstanceIsDestroyedOnceTheProducerReturnsAndTheParameterWithTheProduct() {
        try (ApplicationContext context = ApplicationContext.run()) {
            Instance<Sheet> sheets = context.getBean(CdiBeanContainer.class).createInstance().select(Sheet.class);
            EVENTS.clear();
            Sheet sheet = sheets.get();
            assertEquals(List.of("produce", "folder destroyed"), EVENTS);
            EVENTS.clear();
            sheets.destroy(sheet);
            assertEquals(List.of("pen destroyed", "dispose", "folder destroyed"), EVENTS.stream().sorted().toList());
        }
    }

    @Disabled("Micronaut Core: markDependentAsFactory takes the parameter resolved first for the factory (CONFORMANCE.md, Open points in Core)")
    @Test
    void theParameterOfAProducerOfANormalScopedBeanLivesAsLongAsTheProduct() {
        try (ApplicationContext context = ApplicationContext.run()) {
            Instance<Label> labels = context.getBean(CdiBeanContainer.class).createInstance().select(Label.class);
            EVENTS.clear();
            Label label = labels.get();
            assertEquals(List.of("produce"), EVENTS);
            EVENTS.clear();
            labels.destroy(label);
            assertEquals(List.of("pen destroyed"), EVENTS);
        }
    }
}
