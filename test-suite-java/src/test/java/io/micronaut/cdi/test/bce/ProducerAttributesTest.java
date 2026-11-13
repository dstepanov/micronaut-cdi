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
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.literal.NamedLiteral;
import jakarta.enterprise.inject.spi.Bean;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A producer has the CDI 5.0 attributes it declares (sections 2.8.1, 2.9 and 2.10), not those of the class it produces:
 * Micronaut merges the metadata of the produced class into what a producer is read with, so the producer's record
 * has to shadow the class's even where the producer declares nothing.
 */
class ProducerAttributesTest {

    private static final String EAGER_SOURCE = """
        package attributes.eager;

        import jakarta.enterprise.context.ApplicationScoped;
        import jakarta.enterprise.context.AutoClose;
        import jakarta.enterprise.context.Dependent;
        import jakarta.enterprise.context.Eager;
        import jakarta.enterprise.inject.Produces;
        import jakarta.inject.Named;
        import jakarta.inject.Qualifier;
        import java.lang.annotation.Retention;
        import java.lang.annotation.RetentionPolicy;

        @ApplicationScoped
        @Eager
        @AutoClose
        public class Conn implements AutoCloseable {
            @Override
            public void close() {
            }
        }

        @Qualifier
        @Retention(RetentionPolicy.RUNTIME)
        @interface Second {
        }

        @ApplicationScoped
        class ConnFactory {
            @Produces
            @Dependent
            @Second
            @Named("second")
            Conn second() {
                return new Conn();
            }
        }
        """;

    private static final String RESERVE_SOURCE = """
        package attributes.reserve;

        import jakarta.annotation.Priority;
        import jakarta.enterprise.context.ApplicationScoped;
        import jakarta.enterprise.inject.Produces;
        import jakarta.enterprise.inject.Reserve;
        import jakarta.inject.Named;

        @ApplicationScoped
        @Reserve
        @Priority(1)
        public class Spare {
        }

        @ApplicationScoped
        class SpareFactory {
            @Produces
            @Named("produced")
            Spare produced() {
                return new Spare();
            }
        }
        """;

    @Test
    void aProducerIsNotEagerOrAutoCloseableForTheClassItProduces() {
        try (ApplicationContext context = ExtensionDeployment.start(ExtensionCompilation.of(Nothing.class),
            "attributes.eager.Conn", EAGER_SOURCE)) {
            CdiBeanContainer container = context.getBean(CdiBeanContainer.class);
            Bean<?> produced = container.resolve(container.getBeans(Object.class, NamedLiteral.of("second")));
            assertFalse(produced.isEager(), "the producer declares no @Eager");
            assertFalse(produced.isAutoClose(), "the producer declares no @AutoClose");
            Bean<?> declared = container.getBeans(Object.class, Any.Literal.INSTANCE).stream()
                .filter(bean -> bean.getBeanClass().getName().equals("attributes.eager.Conn"))
                .findFirst().orElseThrow();
            assertTrue(declared.isEager() && declared.isAutoClose(), "the class keeps what it declares");
        }
    }

    @Test
    void aProducerIsNotAReserveForTheClassItProduces() {
        try (ApplicationContext context = ExtensionDeployment.start(ExtensionCompilation.of(Nothing.class),
            "attributes.reserve.Spare", RESERVE_SOURCE)) {
            CdiBeanContainer container = context.getBean(CdiBeanContainer.class);
            Bean<?> produced = container.resolve(container.getBeans(Object.class, NamedLiteral.of("produced")));
            assertFalse(produced.isReserve(), "the producer declares no @Reserve");
        }
    }

    /**
     * An extension that does nothing, so that the compilation runs no other.
     */
    public static final class Nothing implements BuildCompatibleExtension {
    }
}
