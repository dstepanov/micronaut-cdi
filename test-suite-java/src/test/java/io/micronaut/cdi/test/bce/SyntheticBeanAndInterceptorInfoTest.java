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

import io.micronaut.annotation.processing.test.JavaParser;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Alternative;
import jakarta.enterprise.inject.Stereotype;
import jakarta.enterprise.inject.build.compatible.spi.BeanInfo;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.InterceptorInfo;
import jakarta.enterprise.inject.build.compatible.spi.Parameters;
import jakarta.enterprise.inject.build.compatible.spi.Registration;
import jakarta.enterprise.inject.build.compatible.spi.Synthesis;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticBeanCreator;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticComponents;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticInjections;
import jakarta.enterprise.inject.spi.InterceptionType;
import jakarta.enterprise.lang.model.AnnotationInfo;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * What the registration phase is told of a synthetic bean and of an interceptor: a synthetic bean given only
 * {@code Any} and a name has {@code Default} and {@code Named} among its qualifiers (sections 2.2.8, 2.6), its
 * stereotypes, and is an alternative by a stereotype that declares a stereotype that is one (2.7.1.4), selected
 * by its priority; an
 * interceptor with an {@code AroundTimeout} method intercepts timeouts.
 */
class SyntheticBeanAndInterceptorInfoTest {

    @Test
    void theRegistrationPhaseIsToldWhatTheBeanAndTheInterceptorAre() {
        Describing.described.clear();
        try (JavaParser parser = ExtensionCompilation.of(Describing.class)) {
            parser.generate("described.Subject", """
                package described;

                public class Subject {

                    @jakarta.interceptor.InterceptorBinding
                    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
                    @java.lang.annotation.Target({java.lang.annotation.ElementType.TYPE,
                        java.lang.annotation.ElementType.METHOD})
                    public @interface Timed {
                    }

                    @Timed
                    @jakarta.interceptor.Interceptor
                    @jakarta.annotation.Priority(1)
                    public static class Timing {
                        @jakarta.interceptor.AroundTimeout
                        Object timeout(jakarta.interceptor.InvocationContext context) throws Exception {
                            return context.proceed();
                        }
                    }
                }
                """);
        }
        assertEquals(List.of(
            "interceptor timeout=true",
            "synthetic alternative=true qualifiers=[jakarta.enterprise.inject.Any, jakarta.enterprise.inject.Default, "
                + "jakarta.inject.Named] stereotypes=1"), new ArrayList<>(new TreeSet<>(Describing.described)));
    }

    /**
     * A stereotype that is an alternative.
     */
    @Stereotype
    @Alternative
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Mock {
    }

    /**
     * A stereotype that declares the alternative one.
     */
    @Stereotype
    @Mock
    @Retention(RetentionPolicy.RUNTIME)
    public @interface TestDouble {
    }

    /**
     * The synthetic bean.
     */
    public static final class Double {
    }

    /**
     * Creates the synthetic bean.
     */
    public static final class DoubleCreator implements SyntheticBeanCreator<Double> {
        @Override
        public Double create(SyntheticInjections injections, Parameters params) {
            return new Double();
        }
    }

    public static class Describing implements BuildCompatibleExtension {
        static final List<String> described = new ArrayList<>();

        @Synthesis
        public void synthesise(SyntheticComponents components) {
            components.addBean(Double.class).type(Double.class).qualifier(Any.Literal.INSTANCE).name("double")
                .stereotype(TestDouble.class).priority(10).createWith(DoubleCreator.class);
        }

        @Registration(types = Object.class)
        public void bean(BeanInfo bean) {
            if (!bean.isSynthetic()) {
                return;
            }
            List<String> qualifiers = new ArrayList<>();
            for (AnnotationInfo qualifier : bean.qualifiers()) {
                qualifiers.add(qualifier.name());
            }
            java.util.Collections.sort(qualifiers);
            described.add("synthetic alternative=" + bean.isAlternative() + " qualifiers=" + qualifiers
                + " stereotypes=" + bean.stereotypes().size());
        }

        @Registration(types = Object.class)
        public void interceptor(InterceptorInfo interceptor) {
            if (interceptor.declaringClass().name().startsWith("described.")) {
                described.add("interceptor timeout=" + interceptor.intercepts(InterceptionType.AROUND_TIMEOUT));
            }
        }
    }
}
