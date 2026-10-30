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
package io.micronaut.cdi.test.noreflection;

import io.micronaut.cdi.MicronautBeanContainer;
import io.micronaut.context.ApplicationContext;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.context.spi.CreationalContext;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.enterprise.inject.spi.InjectionPoint;
import jakarta.inject.Inject;
import jakarta.inject.Qualifier;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The injectable reference for an injection point of a bean resolves by the qualifiers the point was compiled with,
 * which needs no instance of them and so no reflection, and creates a dependent bean for that point: the bean's own
 * {@code InjectionPoint} is the point it was asked for.
 */
class InjectableReferenceWithoutReflectionTest {

    @Qualifier
    @Retention(RetentionPolicy.RUNTIME)
    @interface Shelf {
        String value();
    }

    interface Book {
    }

    @Dependent
    @Shelf("top")
    static class Atlas implements Book {
        @Inject
        InjectionPoint at;
    }

    @Dependent
    @Shelf("bottom")
    static class Almanac implements Book {
        @Inject
        InjectionPoint at;
    }

    @Dependent
    static class Reader {
        @Inject
        @Shelf("top")
        Book book;
    }

    interface Fragile {
    }

    @Dependent
    static class Shattering implements Fragile {
        Shattering() {
            throw new IllegalStateException("shattered");
        }
    }

    @Dependent
    static class Handler {
        @Inject
        Fragile fragile;
    }

    @Test
    void whatTheCreatedBeanThrowsIsThrownAsItWas() {
        try (ApplicationContext context = ApplicationContext.run()) {
            BeanManager manager = (BeanManager) context.getBean(MicronautBeanContainer.class);
            Bean<?> handler = manager.getBeans(Handler.class).iterator().next();
            InjectionPoint point = handler.getInjectionPoints().iterator().next();
            CreationalContext<?> creation = manager.createCreationalContext(null);
            IllegalStateException thrown = org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> manager.getInjectableReference(point, creation));
            assertEquals("shattered", thrown.getMessage());
            creation.release();
        }
    }

    @Test
    void theReferenceIsResolvedByTheCompiledQualifiersAndCreatedForThePoint() {
        try (ApplicationContext context = ApplicationContext.run()) {
            BeanManager manager = (BeanManager) context.getBean(MicronautBeanContainer.class);
            Bean<?> reader = manager.getBeans(Reader.class).iterator().next();
            InjectionPoint point = reader.getInjectionPoints().iterator().next();
            CreationalContext<?> creation = manager.createCreationalContext(null);
            Atlas atlas = (Atlas) manager.getInjectableReference(point, creation);
            assertNotNull(atlas.at, "the bean was created for no injection point");
            assertEquals(Book.class, atlas.at.getType());
            assertSame(reader, atlas.at.getBean());
            creation.release();
        }
    }
}
