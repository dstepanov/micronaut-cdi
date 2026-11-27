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
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The scope a class declares is its scope: a class that declares the singleton pseudo-scope does not inherit the
 * normal scope of its superclass (section 4.1), and a scope written on a class outright wins over one a stereotype it
 * declares carries (section 2.7.2), a pseudo-scope or a normal scope of the application's own alike.
 */
class PseudoScopeOverNormalScopeTest {

    private static final String SOURCE = """
        package scoped;

        import jakarta.enterprise.context.ApplicationScoped;
        import jakarta.enterprise.context.NormalScope;
        import jakarta.enterprise.inject.Stereotype;
        import jakarta.inject.Singleton;

        import java.lang.annotation.Inherited;
        import java.lang.annotation.Retention;
        import java.lang.annotation.RetentionPolicy;

        public class Subject {

            @NormalScope
            @Inherited
            @Retention(RetentionPolicy.RUNTIME)
            public @interface Cloudy {
            }

            @Stereotype
            @ApplicationScoped
            @Retention(RetentionPolicy.RUNTIME)
            public @interface Service {
            }

            @ApplicationScoped
            public static class Base {
            }

            @Singleton
            public static class Sub extends Base {
                public int calls;
            }

            @Service
            @Singleton
            public static class Stereotyped {
                public int calls;
            }

            @Service
            @Cloudy
            public static class Weather {
            }
        }
        """;

    @Test
    void theScopeAClassDeclaresIsItsScope() throws Exception {
        try (ApplicationContext context = InMemoryDeployment.start("scoped.Subject", SOURCE)) {
            BeanManager manager = context.getBean(BeanManager.class);
            ClassLoader loader = context.getClassLoader();
            assertEquals(jakarta.inject.Singleton.class, scopeOf(manager, loader.loadClass("scoped.Subject$Sub")));
            assertEquals(jakarta.inject.Singleton.class,
                scopeOf(manager, loader.loadClass("scoped.Subject$Stereotyped")));
            assertEquals(loader.loadClass("scoped.Subject$Cloudy"),
                scopeOf(manager, loader.loadClass("scoped.Subject$Weather")));
        }
    }

    private static Class<? extends Annotation> scopeOf(BeanManager manager, Class<?> type) {
        Bean<?> bean = manager.resolve(manager.getBeans(type));
        return bean.getScope();
    }
}
