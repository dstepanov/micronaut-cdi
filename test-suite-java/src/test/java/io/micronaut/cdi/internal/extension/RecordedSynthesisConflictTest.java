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

import io.micronaut.cdi.internal.metadata.CdiRecordedType;
import io.micronaut.cdi.internal.metadata.CdiSyntheticBean;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.RuntimeBeanDefinition;
import io.micronaut.core.annotation.AnnotationClassValue;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.inject.annotation.MutableAnnotationMetadata;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.build.compatible.spi.Parameters;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticBeanCreator;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A synthetic component is identified by its extension and the order the extension described it in, so that the same
 * component two compilations of one application each recorded is registered once. Two records of one identity that
 * describe different components - two compilations whose extension described different beans - are not the same
 * component: the container refuses to start rather than silently keep one of them.
 */
class RecordedSynthesisConflictTest {

    @Test
    void twoIdenticalRecordsAreOneBean() {
        try (ApplicationContext context = ApplicationContext.builder().build()) {
            context.registerBeanDefinition(recordOf("conflict.Extension#1", Alpha.class));
            context.registerBeanDefinition(recordOf("conflict.Extension#1", Alpha.class));
            context.start();
            assertEquals(1, context.getBeansOfType(Alpha.class).size());
        }
    }

    @Test
    void twoDifferentRecordsOfOneIdentityFailTheStart() {
        try (ApplicationContext context = ApplicationContext.builder().build()) {
            context.registerBeanDefinition(recordOf("conflict.Extension#1", Alpha.class));
            context.registerBeanDefinition(recordOf("conflict.Extension#1", Beta.class));
            RuntimeException failure = assertThrows(RuntimeException.class, context::start);
            StringBuilder messages = new StringBuilder();
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                messages.append(cause.getMessage()).append('\n');
            }
            assertTrue(messages.toString().contains("conflict.Extension#1"), messages.toString());
        }
    }

    @Test
    void twoRecordsOfOneIdentityThatDifferInWhichMembersTheyHaveFailTheStart() {
        // as many members each, one named and the other an alternative: no member compares unequal by value
        try (ApplicationContext context = ApplicationContext.builder().build()) {
            context.registerBeanDefinition(recordOf("conflict.Extension#2", Alpha.class, Map.of("name", "named")));
            context.registerBeanDefinition(recordOf("conflict.Extension#2", Alpha.class, Map.of("alternative", true)));
            RuntimeException failure = assertThrows(RuntimeException.class, context::start);
            StringBuilder messages = new StringBuilder();
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                messages.append(cause.getMessage()).append('\n');
            }
            assertTrue(messages.toString().contains("conflict.Extension#2"), messages.toString());
        }
    }

    private static RuntimeBeanDefinition<Creator> recordOf(String id, Class<?> implementation) {
        return recordOf(id, implementation, Map.of());
    }

    private static RuntimeBeanDefinition<Creator> recordOf(String id, Class<?> implementation,
                                                           Map<String, Object> more) {
        MutableAnnotationMetadata metadata = new MutableAnnotationMetadata();
        Map<CharSequence, Object> values = new java.util.LinkedHashMap<>(more);
        values.put("id", id);
        values.put("implementation", new AnnotationClassValue<>(implementation));
        values.put("declaredTypes", new AnnotationValue<?>[] {AnnotationValue.builder(CdiRecordedType.class)
            .member(AnnotationMetadata.VALUE_MEMBER, new AnnotationClassValue<>(implementation)).build()});
        metadata.addDeclaredAnnotation(CdiSyntheticBean.class.getName(), values);
        return RuntimeBeanDefinition.builder(Creator.class, Creator::new)
            .annotationMetadata(metadata)
            .build();
    }

    /**
     * One component an extension described.
     */
    public static final class Alpha {
    }

    /**
     * Another component.
     */
    public static final class Beta {
    }

    /**
     * Creates either.
     */
    public static final class Creator implements SyntheticBeanCreator<Object> {
        @Override
        public Object create(Instance<Object> lookup, Parameters params) {
            return new Alpha();
        }
    }
}
