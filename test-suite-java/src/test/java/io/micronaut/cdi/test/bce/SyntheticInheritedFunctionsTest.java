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

import io.micronaut.cdi.internal.runtime.CdiBeanContainer;
import io.micronaut.context.ApplicationContext;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.Parameters;
import jakarta.enterprise.inject.build.compatible.spi.Synthesis;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticBeanCreator;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticBeanDisposer;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticComponents;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticInjections;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The creation and destruction functions of a synthetic bean may inherit the methods taking
 * {@code SyntheticInjections} from a superclass (CDI 5.0, section 12.5): the container calls the method the class
 * has, wherever it is declared.
 */
class SyntheticInheritedFunctionsTest {

    static final List<String> EVENTS = new ArrayList<>();

    private static final String SOURCE = """
        package inherited;

        public class Anchor {
        }
        """;

    @Test
    void anInheritedCreateAndDisposeAreCalled() {
        EVENTS.clear();
        try (ApplicationContext context = ExtensionDeployment.start(InheritingExtension.class, "inherited.Anchor",
            SOURCE)) {
            Instance<Object> lookup = context.getBean(CdiBeanContainer.class).createInstance();
            Instance.Handle<Product> handle = lookup.select(Product.class).getHandle();
            assertEquals("made", handle.get().state);
            handle.destroy();
        }
        assertEquals(List.of("created", "disposed"), EVENTS);
    }

    /**
     * Adds the bean, created and destroyed by functions whose methods their superclasses declare.
     */
    public static final class InheritingExtension implements BuildCompatibleExtension {
        @Synthesis
        public void synthesise(SyntheticComponents components) {
            components.addBean(Product.class)
                .type(Product.class)
                .scope(Dependent.class)
                .createWith(ProductCreator.class)
                .disposeWith(ProductDisposer.class);
        }
    }

    /**
     * The synthetic bean.
     */
    public static final class Product {
        final String state;

        Product(String state) {
            this.state = state;
        }
    }

    /**
     * Declares the creation method.
     *
     * @param <T> The type created
     */
    public abstract static class BaseCreator<T> implements SyntheticBeanCreator<T> {
        @Override
        public T create(SyntheticInjections injections, Parameters params) {
            EVENTS.add("created");
            return make();
        }

        abstract T make();
    }

    /**
     * Inherits the creation method.
     */
    public static final class ProductCreator extends BaseCreator<Product> {
        @Override
        Product make() {
            return new Product("made");
        }
    }

    /**
     * Declares the destruction method.
     *
     * @param <T> The type destroyed
     */
    public abstract static class BaseDisposer<T> implements SyntheticBeanDisposer<T> {
        @Override
        public void dispose(T instance, SyntheticInjections injections, Parameters params) {
            EVENTS.add("disposed");
        }
    }

    /**
     * Inherits the destruction method.
     */
    public static final class ProductDisposer extends BaseDisposer<Product> {
    }
}
