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
package io.micronaut.cdi.test.index;

import io.micronaut.cdi.internal.runtime.CdiBeanContainer;
import io.micronaut.cdi.test.index.hidden.PublicMid;
import io.micronaut.context.ApplicationContext;
import io.micronaut.core.type.TypeHierarchies;
import io.micronaut.inject.BeanDefinition;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.util.TypeLiteral;
import org.junit.jupiter.api.Test;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The types of a bean are read from Micronaut's type hierarchy record of its class, or of the class its producer
 * produces, for the class over its own variables or the type the producer declared: the definition carries no
 * closure of its own. A class with a supertype no such record can name - one that is not public and of another
 * package - has its closure recorded on its definition instead.
 */
class BeanTypeRecordTest {

    private static final String BEAN_TYPES = "io.micronaut.cdi.internal.metadata.CdiBeanTypes";

    @Dependent
    public static class Shelf extends ArrayList<String> {
    }

    @Dependent
    public static class Mid extends PublicMid<Integer> {
    }

    public static class Crate<T> {
    }

    @Dependent
    public static class Crates {
        @Produces
        Crate<Integer> crate() {
            return new Crate<>();
        }

        @Produces
        List<Long> longs() {
            return List.of();
        }
    }

    @Test
    void theTypesOfABeanClassAreThoseOfItsRecord() {
        try (ApplicationContext context = ApplicationContext.run()) {
            assertTrue(TypeHierarchies.find(Shelf.class).isPresent());
            BeanDefinition<Shelf> definition = context.getBeanDefinition(Shelf.class);
            assertFalse(definition.getAnnotationMetadata().hasAnnotation(BEAN_TYPES),
                "the definition records no closure of its own");
            Set<Bean<?>> beans = context.getBean(CdiBeanContainer.class)
                .getBeans(new TypeLiteral<Collection<String>>() { }.getType());
            assertTrue(beans.stream().anyMatch(bean -> bean.getBeanClass() == Shelf.class),
                "a Shelf is a Collection<String>, from the record of its class");
        }
    }

    @Test
    void theTypesOfAProducedBeanAreThoseOfTheRecordOfTheClassItProduces() {
        try (ApplicationContext context = ApplicationContext.run()) {
            CdiBeanContainer container = context.getBean(CdiBeanContainer.class);
            Set<Bean<?>> longs = container.getBeans(new TypeLiteral<Collection<Long>>() { }.getType());
            assertEquals(1, longs.size(), "a List<Long> is a Collection<Long>, from the record of List");
            assertFalse(container.getBeans(new TypeLiteral<Collection<String>>() { }.getType())
                .contains(longs.iterator().next()), "and no Collection<String>");
            assertEquals(1, container.getBeans(new TypeLiteral<Crate<Integer>>() { }.getType()).size());
            for (BeanDefinition<?> definition : context.getBeanDefinitions(Crate.class)) {
                assertFalse(definition.getAnnotationMetadata().hasAnnotation(BEAN_TYPES),
                    "the producer records no closure of its own");
            }
        }
    }

    @Test
    void aSupertypeNoRecordCanNameIsRecordedOnTheDefinition() {
        try (ApplicationContext context = ApplicationContext.run()) {
            BeanDefinition<Mid> definition = context.getBeanDefinition(Mid.class);
            assertTrue(definition.getAnnotationMetadata().hasAnnotation(BEAN_TYPES),
                "the closure of a class with a package-private supertype of another package is on its definition");
            Bean<?> bean = context.getBean(CdiBeanContainer.class).getBeans(Mid.class).iterator().next();
            assertTrue(bean.getTypes().stream().anyMatch(type -> type instanceof ParameterizedType p
                    && ((Class<?>) p.getRawType()).getName().equals("io.micronaut.cdi.test.index.hidden.HiddenBase")
                    && p.getActualTypeArguments()[0] == Integer.class),
                "the package-private supertype is a bean type: " + bean.getTypes());
            List<Type> types = new ArrayList<>(bean.getTypes());
            assertTrue(types.contains(Object.class));
        }
    }
}
