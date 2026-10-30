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
package io.micronaut.cdi.test.synthetic;

import io.micronaut.cdi.internal.runtime.CdiBeanContainer;
import io.micronaut.cdi.test.extension.Box;
import io.micronaut.cdi.test.extension.Crate;
import io.micronaut.context.ApplicationContext;
import io.micronaut.core.type.Argument;
import io.micronaut.inject.BeanDefinition;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.util.TypeLiteral;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A synthetic bean is a bean of exactly the types its extension declared (section 2.10.5), each as it was declared:
 * a parameterized type keeps its type arguments, and the implementation class contributes no type the extension did
 * not name. The definition Micronaut resolves the bean by carries the same arguments, so that it is not left to work
 * them out from the generic signature of the implementation class.
 */
class SyntheticBeanTypesTest {

    private static final Type SUPPLIER_OF_STRING = new TypeLiteral<Supplier<String>>() { }.getType();

    @Test
    void aParameterizedBeanTypeKeepsItsTypeArguments() {
        try (ApplicationContext context = ApplicationContext.run()) {
            CdiBeanContainer container = context.getBean(CdiBeanContainer.class);
            Bean<?> box = boxBean(container);
            assertEquals(Set.of(SUPPLIER_OF_STRING, Object.class), box.getTypes());
            assertEquals("boxed", container.createInstance()
                .select(new TypeLiteral<Supplier<String>>() { }).get().get());
            assertTrue(container.createInstance().select(new TypeLiteral<Supplier<Integer>>() { }).isUnsatisfied(),
                "the bean is a supplier of strings, not of whatever the implementation class leaves open");
        }
    }

    @Test
    void theDefinitionCarriesTheDeclaredTypeArguments() {
        try (ApplicationContext context = ApplicationContext.run()) {
            BeanDefinition<?> definition = context.getBeanDefinitions(Supplier.class).stream()
                .filter(candidate -> Box.class.equals(candidate.getBeanType()))
                .findFirst().orElseThrow();
            assertEquals(List.of(Argument.STRING), definition.getTypeArguments(Supplier.class));
        }
    }

    @Test
    void theImplementationClassContributesNoUndeclaredType() {
        try (ApplicationContext context = ApplicationContext.run()) {
            CdiBeanContainer container = context.getBean(CdiBeanContainer.class);
            Set<Bean<?>> crates = container.getBeans(Crate.class);
            assertEquals(1, crates.size());
            assertEquals(Set.of(Crate.class, Object.class), crates.iterator().next().getTypes());
            assertTrue(container.getBeans(Runnable.class, Any.Literal.INSTANCE).stream()
                    .noneMatch(bean -> bean.getBeanClass() == Crate.class),
                "Runnable was not declared a bean type of the crate");
        }
    }

    private static Bean<?> boxBean(CdiBeanContainer container) {
        return container.getBeans(Object.class, Any.Literal.INSTANCE).stream()
            .filter(bean -> bean.getBeanClass() == Box.class)
            .findFirst().orElseThrow();
    }
}
