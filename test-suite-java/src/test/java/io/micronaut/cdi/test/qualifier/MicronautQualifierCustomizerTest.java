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
package io.micronaut.cdi.test.qualifier;

import io.micronaut.cdi.MicronautBeanContainer;
import io.micronaut.cdi.internal.runtime.CdiContextConfigurer;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.ApplicationContextBuilder;
import io.micronaut.context.BeanResolutionCustomizer;
import io.micronaut.context.Qualifier;
import io.micronaut.context.annotation.Prototype;
import io.micronaut.core.type.Argument;
import io.micronaut.inject.BeanDefinition;
import io.micronaut.inject.qualifiers.Qualifiers;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * An application's resolution customizer that chooses among beans of Micronaut's own by the Micronaut qualifier of
 * the lookup is told that qualifier by a lookup of the container, as it is by {@code BeanContext.getBean}.
 */
class MicronautQualifierCustomizerTest {

    interface Gauge {
    }

    @Prototype
    static class FuelGauge implements Gauge {
    }

    @Prototype
    static class OilGauge implements Gauge {
    }

    /**
     * Chooses the oil gauge where the lookup is qualified, and nothing otherwise.
     */
    static final class QualifiedChoice implements BeanResolutionCustomizer {
        @Override
        public <T> Optional<BeanDefinition<T>> resolveNonUniqueBean(Argument<T> beanType,
                                                                    @Nullable Qualifier<T> qualifier,
                                                                    Collection<BeanDefinition<T>> candidates) {
            if (qualifier != null && beanType.getType() == Gauge.class) {
                return candidates.stream().filter(candidate -> candidate.getBeanType() == OilGauge.class).findFirst();
            }
            return Optional.empty();
        }
    }

    @Test
    void theCustomizerIsToldTheMicronautQualifierOfTheLookup() {
        // installed in place of the one this module installed as the builder was created, and wrapped by it again
        ApplicationContextBuilder builder = ApplicationContext.builder().beanResolutionCustomizer(new QualifiedChoice());
        new CdiContextConfigurer().configure(builder);
        try (ApplicationContext context = builder.start()) {
            Qualifier<Gauge> gauges = Qualifiers.byType(Gauge.class);
            assertEquals(OilGauge.class, context.getBean(Gauge.class, gauges).getClass());
            assertEquals(OilGauge.class, context.getBean(MicronautBeanContainer.class).createInstance()
                .select(Gauge.class, gauges).get().getClass());
        }
    }
}
