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

import io.micronaut.annotation.processing.test.JavaParser;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.DefaultBeanDefinitionsProvider;
import io.micronaut.inject.BeanDefinitionReference;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.CDI;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaFileObject;
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The builder generated for an annotation type the application registers is found in the class loader of the
 * application, where Micronaut's own registry, which reads the class loader Micronaut was loaded by, does not see
 * it: the qualifier of a bean is reported as the instance that builder makes, not one made reflectively.
 */
class ApplicationLoaderAnnotationBuilderTest {

    private static final String SERVICES = "META-INF/micronaut/";
    private static final String BUILDERS = SERVICES + "io.micronaut.core.annotation.AnnotationBuilder/";

    @TempDir
    Path resources;

    @Test
    void theQualifiersOfABeanAreMadeByTheBuilderOfTheApplicationLoader() throws Exception {
        Iterable<? extends JavaFileObject> files;
        try (JavaParser parser = new JavaParser()) {
            files = parser.generate("loaderbuilder.Shop", """
                package loaderbuilder;

                @io.micronaut.core.annotation.RegisterAnnotations(Shop.Region.class)
                @Shop.Region("east")
                @jakarta.enterprise.context.Dependent
                public class Shop {

                    @jakarta.inject.Qualifier
                    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
                    public @interface Region {
                        String value();
                    }
                }
                """);
        }
        Map<String, byte[]> classes = new LinkedHashMap<>();
        boolean registered = false;
        for (JavaFileObject file : files) {
            if (file.getKind() == JavaFileObject.Kind.CLASS) {
                try (InputStream in = file.openInputStream()) {
                    classes.put(CompiledClasses.binaryName(file), in.readAllBytes());
                }
            } else {
                String name = file.getName();
                int at = name.indexOf(BUILDERS);
                if (at >= 0) {
                    // only the builders the compilation registered: the rest it generated is on the classpath
                    Path written = resources.resolve(name.substring(at));
                    Files.createDirectories(written.getParent());
                    try (InputStream in = file.openInputStream()) {
                        Files.write(written, in.readAllBytes());
                    }
                    registered = true;
                }
            }
        }
        assertTrue(registered, "the compilation registered no builder");
        URL services = resources.resolve(SERVICES).toUri().toURL();
        ClassLoader loader = new ClassLoader(getClass().getClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] bytes = classes.get(name);
                if (bytes == null) {
                    throw new ClassNotFoundException(name);
                }
                return defineClass(name, bytes, 0, bytes.length);
            }

            @Override
            protected Enumeration<URL> findResources(String name) throws IOException {
                return name.equals(SERVICES) ? Collections.enumeration(List.of(services))
                    : Collections.emptyEnumeration();
            }
        };
        try (ApplicationContext context = ApplicationContext.builder()
            .classLoader(loader)
            .beanDefinitionsProvider(classLoader -> {
                List<BeanDefinitionReference<?>> references =
                    new ArrayList<>(new DefaultBeanDefinitionsProvider().provide(classLoader));
                for (String name : classes.keySet()) {
                    if (name.startsWith("loaderbuilder.") && name.endsWith("$Definition")) {
                        try {
                            references.add((BeanDefinitionReference<?>) loader.loadClass(name)
                                .getDeclaredConstructor().newInstance());
                        } catch (ReflectiveOperationException e) {
                            throw new IllegalStateException(e);
                        }
                    }
                }
                return references;
            })
            .build()
            .start()) {
            Class<?> shop = loader.loadClass("loaderbuilder.Shop");
            Bean<?> bean = CDI.current().getBeanContainer().getBeans(shop, Any.Literal.INSTANCE).iterator().next();
            Annotation region = null;
            for (Annotation qualifier : bean.getQualifiers()) {
                if (qualifier.annotationType().getName().equals("loaderbuilder.Shop$Region")) {
                    region = qualifier;
                }
            }
            assertNotNull(region, "the qualifiers " + bean.getQualifiers());
            assertFalse(Proxy.isProxyClass(region.getClass()), "made reflectively: " + region.getClass());
            assertEquals("east", region.annotationType().getMethod("value").invoke(region));
        }
    }
}
