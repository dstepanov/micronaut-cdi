/* Licensed under the Apache License, Version 2.0. */
package io.micronaut.cdi.test;

import io.micronaut.annotation.processing.test.JavaParser;
import io.micronaut.cdi.internal.runtime.CdiBeanContainer;
import io.micronaut.cdi.processor.extension.BuildCompatibleExtensionVisitor;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.DefaultBeanDefinitionsProvider;
import io.micronaut.inject.BeanDefinitionReference;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.build.compatible.spi.*;
import jakarta.enterprise.inject.spi.InjectionPoint;
import jakarta.enterprise.lang.model.types.Type;
import jakarta.enterprise.util.TypeLiteral;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.tools.JavaFileObject;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Synthetic bean types are independent from the bean class declared through addBean. */
public class SyntheticDeclaredTypesTest {
    private static final String SOURCE = """
        package syntheticshapes;
        @jakarta.enterprise.context.Dependent
        public class Requests {
            @jakarta.inject.Inject @jakarta.inject.Named("matrix") public String[][] matrix;
            @jakarta.inject.Inject @io.micronaut.cdi.test.SyntheticDeclaredTypesTest.Prefix("strings")
            public java.util.List<String> strings;
        }
        """;

    @AfterEach
    void restoreExtensions() {
        BuildCompatibleExtensionVisitor.overrideExtensions(null);
    }

    @Test
    void exactArrayAndParameterizedTypesRetainTheirRequestingMetadata() throws Exception {
        BuildCompatibleExtensionVisitor.overrideExtensions(List.of(new ShapesExtension()));
        try (ApplicationContext context = compileAndStart()) {
            Class<?> requests = context.getClassLoader().loadClass("syntheticshapes.Requests");
            Object bean = context.getBean(requests);
            assertArrayEquals(new String[]{"matrix"}, ((String[][]) requests.getField("matrix").get(bean))[0]);
            assertEquals(List.of("strings"), requests.getField("strings").get(bean));
            CdiBeanContainer container = context.getBean(CdiBeanContainer.class);
            var matrix = container.getBeans(String[][].class, new NamedLiteral("matrix")).iterator().next();
            assertEquals(Object.class, matrix.getBeanClass());
            assertEquals(Set.of(String[][].class, Object.class), matrix.getTypes());
            assertTrue(container.getBeans(new TypeLiteral<List<String>>() { }.getType(), new PrefixLiteral("strings"))
                .iterator().next().getTypes().contains(new TypeLiteral<List<String>>() { }.getType()));
            assertTrue(container.getBeans(new TypeLiteral<List<Integer>>() { }.getType(), new PrefixLiteral("strings")).isEmpty());
            assertTrue(container.getBeans(new TypeLiteral<List<String>>() { }.getType(), new PrefixLiteral("wrong")).isEmpty(),
                "The imported qualifier's Nonbinding member was removed during enhancement");
        }
    }

    public static final class ShapesExtension implements BuildCompatibleExtension {
        private Type matrix;
        private Type strings;

        @Discovery
        public void discover(ScannedClasses classes) {
            classes.add(Prefix.class.getCanonicalName());
        }

        @Enhancement(types = Prefix.class)
        public void binding(MethodConfig method) {
            method.removeAnnotation(annotation -> annotation.name().equals(jakarta.enterprise.util.Nonbinding.class.getName())
                || annotation.name().equals("io.micronaut.context.annotation.NonBinding"));
        }

        @Enhancement(types = Object.class, withSubtypes = true)
        public void collect(ClassConfig bean) {
            if (!bean.info().name().equals("syntheticshapes.Requests")) return;
            for (FieldConfig field : bean.fields()) {
                if (field.info().name().equals("matrix")) matrix = field.info().type();
                if (field.info().name().equals("strings")) strings = field.info().type();
            }
        }

        @Synthesis
        public void create(SyntheticComponents components) {
            components.addBean(Object.class).type(matrix).qualifier(new NamedLiteral("matrix"))
                .createWith(MatrixCreator.class);
            components.addBean(Object.class).type(strings)
                .qualifier(AnnotationBuilder.of(Prefix.class).value("strings").build())
                .createWith(StringsCreator.class);
        }
    }

    public static final class MatrixCreator implements SyntheticBeanCreator<Object> {
        @Override
        public Object create(Instance<Object> lookup, Parameters parameters) {
            InjectionPoint point = lookup.select(InjectionPoint.class).get();
            assertEquals(String[][].class, point.getType());
            assertEquals("matrix", point.getMember().getName());
            return new String[][]{{point.getMember().getName()}};
        }
    }

    public static final class StringsCreator implements SyntheticBeanCreator<Object> {
        @Override
        public Object create(Instance<Object> lookup, Parameters parameters) {
            InjectionPoint point = lookup.select(InjectionPoint.class).get();
            assertEquals(new TypeLiteral<List<String>>() { }.getType(), point.getType());
            return List.of(point.getMember().getName());
        }
    }

    public static final class NamedLiteral extends jakarta.enterprise.util.AnnotationLiteral<jakarta.inject.Named>
        implements jakarta.inject.Named {
        private final String value;
        public NamedLiteral(String value) { this.value = value; }
        @Override public String value() { return value; }
    }

    @jakarta.inject.Qualifier
    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
    public @interface Prefix {
        @jakarta.enterprise.util.Nonbinding String value();
    }

    public static final class PrefixLiteral extends jakarta.enterprise.util.AnnotationLiteral<Prefix> implements Prefix {
        private final String value;
        public PrefixLiteral(String value) { this.value = value; }
        @Override public String value() { return value; }
    }

    private static ApplicationContext compileAndStart() throws Exception {
        Map<String, byte[]> classes = new LinkedHashMap<>();
        Map<String, byte[]> resources = new LinkedHashMap<>();
        try (JavaParser parser = new JavaParser()) {
            for (JavaFileObject file : parser.generate("syntheticshapes.Requests", SOURCE)) {
                String name = file.getName();
                if (file.getKind() == JavaFileObject.Kind.OTHER && name.contains("/CLASS_OUTPUT/")) {
                    try (var input = file.openInputStream()) {
                        resources.put(name.substring(name.indexOf("/CLASS_OUTPUT/") + "/CLASS_OUTPUT/".length()), input.readAllBytes());
                    }
                    continue;
                }
                if (file.getKind() != JavaFileObject.Kind.CLASS) continue;
                name = name.substring(name.indexOf("/CLASS_OUTPUT/") + "/CLASS_OUTPUT/".length(),
                    name.length() - ".class".length()).replace('/', '.');
                try (var input = file.openInputStream()) { classes.put(name, input.readAllBytes()); }
            }
        }
        ClassLoader loader = new ClassLoader(SyntheticDeclaredTypesTest.class.getClassLoader()) {
            @Override public java.io.InputStream getResourceAsStream(String name) {
                byte[] bytes = resources.get(name);
                return bytes == null ? super.getResourceAsStream(name) : new java.io.ByteArrayInputStream(bytes);
            }
            @Override protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] bytes = classes.get(name);
                if (bytes == null) throw new ClassNotFoundException(name);
                return defineClass(name, bytes, 0, bytes.length);
            }
        };
        return ApplicationContext.builder().classLoader(loader).beanDefinitionsProvider(classLoader -> {
            List<BeanDefinitionReference<?>> references = new ArrayList<>(new DefaultBeanDefinitionsProvider().provide(classLoader));
            for (String name : classes.keySet()) {
                if (!name.endsWith("$Definition")) continue;
                try { references.add((BeanDefinitionReference<?>) loader.loadClass(name).getDeclaredConstructor().newInstance()); }
                catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
            }
            return references;
        }).start();
    }
}
