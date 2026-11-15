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
package io.micronaut.cdi.internal.runtime;

import io.micronaut.cdi.internal.metadata.CdiAnnotationTypes;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.BeanDefinitionRegistry;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.inject.BeanDefinitionReference;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.util.Nonbinding;
import jakarta.inject.Inject;
import jakarta.inject.Qualifier;
import jakarta.interceptor.InterceptorBinding;
import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A qualifier or binding type that only a member of a bean uses - an injection point, a method - is known to the
 * container from the class level metadata of the bean, which the processor records it on: no definition is loaded to
 * read its injection points and executable methods.
 */
class MemberAnnotationTypesTest {

    @Qualifier
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.METHOD, ElementType.TYPE})
    public @interface Grade {
        String value();

        @Nonbinding
        String note() default "";
    }

    @Qualifier
    @Retention(RetentionPolicy.CLASS)
    @Target({ElementType.FIELD, ElementType.PARAMETER})
    public @interface Compiled {
    }

    @InterceptorBinding
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.METHOD, ElementType.TYPE})
    public @interface Audited {
    }

    public static class Material {
    }

    @Dependent
    public static class Workshop {
        @Inject
        @Grade(value = "fine", note = "polished")
        Instance<Material> material;

        @Inject
        @Compiled
        Instance<Material> compiled;

        @Audited
        public void work() {
        }
    }

    @Test
    void theTypesAMemberUsesAreRecordedOnTheClass() {
        try (ApplicationContext context = ApplicationContext.run()) {
            BeanDefinitionReference<?> workshop = ((BeanDefinitionRegistry) context).getBeanDefinitionReferences()
                .stream().filter(reference -> Workshop.class.isAssignableFrom(reference.getBeanType())).findFirst().orElseThrow();
            AnnotationValue<CdiAnnotationTypes> recorded =
                workshop.getAnnotationMetadata().getAnnotation(CdiAnnotationTypes.class);
            assertNotNull(recorded, "the processor records the types the members use on the class");
            assertTrue(List.of(recorded.stringValues(CdiAnnotationTypes.QUALIFIERS)).contains(Grade.class.getName()));
            assertTrue(List.of(recorded.stringValues(CdiAnnotationTypes.BINDINGS)).contains(Audited.class.getName()));

            CompiledAnnotationTypes types = CompiledAnnotationTypes.of(context);
            assertEquals(Boolean.TRUE, types.isQualifier(Grade.class.getName()));
            assertEquals(Set.of("note"), types.nonbindingOf(Grade.class.getName()));
            assertEquals(Boolean.FALSE, types.isMarker(Grade.class.getName()));
            assertEquals(Boolean.TRUE, types.isRetainedAtRuntime(Grade.class.getName()));
            // the retention the interface declares, which an occurrence the compiler read does not carry
            assertEquals(Boolean.TRUE, types.isQualifier(Compiled.class.getName()));
            assertEquals(Boolean.FALSE, types.isRetainedAtRuntime(Compiled.class.getName()));
            assertEquals(Boolean.TRUE, types.isInterceptorBinding(Audited.class.getName()));
            assertEquals(Boolean.TRUE, types.isMarker(Audited.class.getName()));
        }
    }
}
