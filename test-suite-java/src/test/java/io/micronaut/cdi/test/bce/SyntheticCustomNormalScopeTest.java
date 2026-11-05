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
import jakarta.enterprise.context.NormalScope;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.Parameters;
import jakarta.enterprise.inject.build.compatible.spi.Synthesis;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticBeanCreator;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticComponents;
import jakarta.enterprise.inject.spi.BeanManager;
import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * A synthetic bean of a normal scope of the application's own is held the way a class bean of the scope is: by the
 * context registered for the scope, and where none is, by the application scope - one instance for every lookup.
 */
class SyntheticCustomNormalScopeTest {

    private static final String SOURCE = """
        package cloudy;

        @jakarta.enterprise.context.Dependent
        public class Subject {
        }
        """;

    @Test
    void withNoContextOfTheScopeASyntheticBeanIsHeldInTheApplicationScope() {
        Sky.CREATED.set(0);
        try (ApplicationContext context = ExtensionDeployment.start(CloudySynthesis.class, "cloudy.Subject",
            SOURCE)) {
            BeanManager manager = context.getBean(BeanManager.class);
            Sky first = manager.createInstance().select(Sky.class).get();
            Sky second = manager.createInstance().select(Sky.class).get();
            assertSame(first, second, "one instance for every lookup");
            assertEquals(1, Sky.CREATED.get());
            assertEquals(Cloudy.class, manager.resolve(manager.getBeans(Sky.class)).getScope(),
                "the scope the extension named");
        }
    }

    /**
     * A normal scope of the application, which no context is registered for.
     */
    @NormalScope
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
    public @interface Cloudy {
    }

    public static final class CloudySynthesis implements BuildCompatibleExtension {

        @Synthesis
        public void synthesize(SyntheticComponents components) {
            components.addBean(Sky.class).type(Sky.class).scope(Cloudy.class).createWith(SkyCreator.class);
        }
    }

    public static final class Sky {
        static final AtomicInteger CREATED = new AtomicInteger();
    }

    public static final class SkyCreator implements SyntheticBeanCreator<Sky> {
        @Override
        public Sky create(Instance<Object> lookup, Parameters params) {
            Sky.CREATED.incrementAndGet();
            return new Sky();
        }
    }
}
