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
package io.micronaut.cdi.test.index;

import io.micronaut.annotation.processing.test.JavaParser;
import io.micronaut.cdi.internal.runtime.CdiBeanContainer;
import io.micronaut.cdi.test.index.hidden.PublicMid;
import io.micronaut.context.ApplicationContext;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.type.TypeHierarchies;
import io.micronaut.inject.BeanDefinition;
import io.micronaut.inject.BeanDefinitionReference;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.util.TypeLiteral;
import org.junit.jupiter.api.Test;

import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The types of a bean are read from Micronaut's type hierarchy record of its class, or of the class its producer
 * produces, for the class over its own variables or the type the producer declared: the definition carries no
 * closure of its own. A class with a supertype no such record can name - one that is not public and of another
 * package - has its closure recorded on its definition instead.
 */
class BeanTypeRecordTest {

    private static final String BEAN_TYPES = "io.micronaut.cdi.internal.metadata.CdiBeanTypes";

    @Dependent
    public static class Shelf extends ArrayList<String> {
    }

    @Dependent
    public static class Mid extends PublicMid<Integer> {
    }

    public static class Crate<T> {
    }

    @Dependent
    public static class Crates {
        @Produces
        Crate<Integer> crate() {
            return new Crate<>();
        }

        @Produces
        List<Long> longs() {
            return List.of();
        }
    }

    @Test
    void theTypesOfABeanClassAreThoseOfItsRecord() {
        try (ApplicationContext context = ApplicationContext.run()) {
            assertTrue(TypeHierarchies.find(Shelf.class).isPresent());
            BeanDefinition<Shelf> definition = context.getBeanDefinition(Shelf.class);
            assertFalse(definition.getAnnotationMetadata().hasAnnotation(BEAN_TYPES),
                "the definition records no closure of its own");
            Set<Bean<?>> beans = context.getBean(CdiBeanContainer.class)
                .getBeans(new TypeLiteral<Collection<String>>() { }.getType());
            assertTrue(beans.stream().anyMatch(bean -> bean.getBeanClass() == Shelf.class),
                "a Shelf is a Collection<String>, from the record of its class");
        }
    }

    @Test
    void theTypesOfAProducedBeanAreThoseOfTheRecordOfTheClassItProduces() {
        try (ApplicationContext context = ApplicationContext.run()) {
            CdiBeanContainer container = context.getBean(CdiBeanContainer.class);
            Set<Bean<?>> longs = container.getBeans(new TypeLiteral<Collection<Long>>() { }.getType());
            assertEquals(1, longs.size(), "a List<Long> is a Collection<Long>, from the record of List");
            assertFalse(container.getBeans(new TypeLiteral<Collection<String>>() { }.getType())
                .contains(longs.iterator().next()), "and no Collection<String>");
            assertEquals(1, container.getBeans(new TypeLiteral<Crate<Integer>>() { }.getType()).size());
            for (BeanDefinition<?> definition : context.getBeanDefinitions(Crate.class)) {
                assertFalse(definition.getAnnotationMetadata().hasAnnotation(BEAN_TYPES),
                    "the producer records no closure of its own");
            }
        }
    }

    @Test
    void aSupertypeNoRecordCanNameIsRecordedOnTheDefinition() {
        try (ApplicationContext context = ApplicationContext.run()) {
            BeanDefinition<Mid> definition = context.getBeanDefinition(Mid.class);
            assertTrue(definition.getAnnotationMetadata().hasAnnotation(BEAN_TYPES),
                "the closure of a class with a package-private supertype of another package is on its definition");
            Bean<?> bean = context.getBean(CdiBeanContainer.class).getBeans(Mid.class).iterator().next();
            assertTrue(bean.getTypes().stream().anyMatch(type -> type instanceof ParameterizedType p
                    && ((Class<?>) p.getRawType()).getName().equals("io.micronaut.cdi.test.index.hidden.HiddenBase")
                    && p.getActualTypeArguments()[0] == Integer.class),
                "the package-private supertype is a bean type: " + bean.getTypes());
            List<Type> types = new ArrayList<>(bean.getTypes());
            assertTrue(types.contains(Object.class));
        }
    }

    @Test
    void aPublicSupertypeWhoseArgumentNoRecordCanNameIsRecordedOnTheDefinition() throws Exception {
        // compiled apart: a definition Micronaut writes for such a class names the package-private argument among
        // the type arguments of the bean and cannot be loaded, so the bean cannot be in this suite's compilation
        Map<String, byte[]> classes = compile(Map.of(
            "hidden.PublicSupplier", """
                package hidden;
                public abstract class PublicSupplier implements java.util.function.Supplier<HiddenSupplied> {
                    public HiddenSupplied get() { return new HiddenSupplied(); }
                }
                """,
            "hidden.HiddenSupplied", """
                package hidden;
                final class HiddenSupplied {
                }
                """,
            "feeding.Feeder", """
                package feeding;
                @jakarta.enterprise.context.Dependent
                public class Feeder extends hidden.PublicSupplier {
                }
                """));
        ClassLoader loader = new ClassLoader(BeanTypeRecordTest.class.getClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] bytes = classes.get(name);
                if (bytes == null) {
                    throw new ClassNotFoundException(name);
                }
                return defineClass(name, bytes, 0, bytes.length);
            }
        };
        BeanDefinitionReference<?> reference = (BeanDefinitionReference<?>) loader
            .loadClass("feeding.$Feeder$Definition").getDeclaredConstructor().newInstance();
        assertTrue(reference.getAnnotationMetadata().hasAnnotation(BEAN_TYPES),
            "the closure of a class whose public supertype names a package-private class of another package is on "
                + "its definition, the record of the class leaving that supertype out");
        List<AnnotationValue<Annotation>> closure = reference.getAnnotationMetadata().findAnnotation(BEAN_TYPES)
            .orElseThrow().getAnnotations("value");
        assertTrue(closure.stream().anyMatch(type -> "java.util.function.Supplier".equals(type.stringValue().orElse(null))),
            "the supplier is in the closure: " + closure);
    }

    private static Map<String, byte[]> compile(Map<String, String> sources) {
        JavaFileObject[] files = sources.entrySet().stream()
            .map(entry -> new SimpleJavaFileObject(URI.create("string:///" + entry.getKey().replace('.', '/') + ".java"),
                JavaFileObject.Kind.SOURCE) {
                @Override
                public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                    return entry.getValue();
                }
            })
            .toArray(JavaFileObject[]::new);
        Map<String, byte[]> classes = new LinkedHashMap<>();
        try (JavaParser parser = new JavaParser()) {
            for (JavaFileObject file : parser.generate(files)) {
                if (file.getKind() == JavaFileObject.Kind.CLASS) {
                    String name = file.getName();
                    name = name.substring(name.indexOf("/CLASS_OUTPUT/") + "/CLASS_OUTPUT/".length(),
                        name.length() - ".class".length()).replace('/', '.');
                    try (InputStream in = file.openInputStream()) {
                        classes.put(name, in.readAllBytes());
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }
            }
        }
        return classes;
    }
}
