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
package io.micronaut.cdi.internal.extension;

import io.micronaut.cdi.internal.metadata.CdiSyntheticBean;
import io.micronaut.cdi.internal.runtime.CdiBeanContainer;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.RuntimeBeanDefinition;
import io.micronaut.core.annotation.AnnotationClassValue;
import io.micronaut.inject.annotation.MutableAnnotationMetadata;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.build.compatible.spi.Parameters;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticBeanCreator;
import jakarta.enterprise.inject.spi.Bean;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A synthetic bean recorded by an earlier processor - its declared types written as classes, before they were
 * written as type records - is still a bean of exactly those types: a library compiled before is read as it was.
 */
class RecordedSynthesisLegacyTypesTest {

    @Test
    void typesRecordedAsClassesAreTheBeanTypes() {
        MutableAnnotationMetadata metadata = new MutableAnnotationMetadata();
        metadata.addDeclaredAnnotation(CdiSyntheticBean.class.getName(), Map.of(
            "id", "legacy.Extension#1",
            "implementation", new AnnotationClassValue<>(Pump.class),
            "types", new AnnotationClassValue<?>[] {new AnnotationClassValue<>(Runnable.class)}));
        try (ApplicationContext context = ApplicationContext.builder().build()) {
            context.registerBeanDefinition(RuntimeBeanDefinition.builder(Creator.class, Creator::new)
                .annotationMetadata(metadata)
                .build());
            context.start();
            CdiBeanContainer container = context.getBean(CdiBeanContainer.class);
            Set<Bean<?>> beans = container.getBeans(Runnable.class);
            Bean<?> pump = beans.stream().filter(bean -> bean.getBeanClass() == Pump.class).findFirst().orElseThrow();
            assertEquals(Set.of(Runnable.class, Object.class), pump.getTypes());
        }
    }

    /**
     * The implementation, exposed as {@code Runnable}.
     */
    public static final class Pump implements Runnable {
        @Override
        public void run() {
        }
    }

    /**
     * Creates the pump.
     */
    public static final class Creator implements SyntheticBeanCreator<Pump> {
        @Override
        public Pump create(Instance<Object> lookup, Parameters params) {
            return new Pump();
        }
    }
}
