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

import io.micronaut.context.ApplicationContext;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Default;
import jakarta.enterprise.inject.InjectionException;
import jakarta.enterprise.inject.spi.Annotated;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.enterprise.inject.spi.InjectionPoint;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.reflect.Member;
import java.lang.reflect.Type;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@code BeanManager.validate} reports an injection point no bean satisfies, or more than one does, rather than
 * accepting any it is handed.
 */
class BeanManagerValidateTest {

    interface Nowhere {
    }

    interface Lamp {
    }

    @Dependent
    static class DeskLamp implements Lamp {
    }

    @Dependent
    static class FloorLamp implements Lamp {
    }

    @Dependent
    static class Desk {
    }

    @Test
    void anInjectionPointNoBeanOrMoreThanOneSatisfiesIsReported() {
        try (ApplicationContext context = ApplicationContext.run()) {
            BeanManager manager = context.getBean(BeanManager.class);
            assertThrows(InjectionException.class, () -> manager.validate(pointOf(Nowhere.class)));
            assertThrows(InjectionException.class, () -> manager.validate(pointOf(Lamp.class)));
            assertDoesNotThrow(() -> manager.validate(pointOf(Desk.class)));
        }
    }

    private static InjectionPoint pointOf(Type type) {
        return new InjectionPoint() {
            @Override
            public Type getType() {
                return type;
            }

            @Override
            public Set<Annotation> getQualifiers() {
                return Set.of(Default.Literal.INSTANCE);
            }

            @Override
            public Bean<?> getBean() {
                return null;
            }

            @Override
            public Member getMember() {
                return null;
            }

            @Override
            public Annotated getAnnotated() {
                return null;
            }

            @Override
            public boolean isDelegate() {
                return false;
            }

            @Override
            public boolean isTransient() {
                return false;
            }
        };
    }
}
