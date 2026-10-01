package io.micronaut.cdi.test;

import io.micronaut.annotation.processing.test.JavaParser;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.DefaultBeanDefinitionsProvider;
import io.micronaut.inject.BeanDefinitionReference;

import javax.tools.JavaFileObject;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A deployment compiled in memory and started as a container: the way to hold the container to rejecting a
 * deployment as it starts, which a class compiled with the rest of this suite cannot be, since every test's
 * container would then be rejected.
 */
final class InMemoryDeployment {

    private InMemoryDeployment() {
    }

    /**
     * Compiles the source and starts a container with the beans of this suite and those it compiled to.
     *
     * @param className The name of the class the source declares
     * @param source    The source
     * @return The started context
     */
    static ApplicationContext start(String className, String source) {
        Map<String, byte[]> classes = new LinkedHashMap<>();
        try (JavaParser parser = new JavaParser()) {
            for (JavaFileObject file : parser.generate(className, source)) {
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
        String packageName = className.substring(0, className.lastIndexOf('.') + 1);
        ClassLoader loader = new ClassLoader(InMemoryDeployment.class.getClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] bytes = classes.get(name);
                if (bytes == null) {
                    throw new ClassNotFoundException(name);
                }
                return defineClass(name, bytes, 0, bytes.length);
            }
        };
        return ApplicationContext.builder()
            .classLoader(loader)
            .beanDefinitionsProvider(classLoader -> {
                List<BeanDefinitionReference<?>> references =
                    new ArrayList<>(new DefaultBeanDefinitionsProvider().provide(classLoader));
                for (String name : classes.keySet()) {
                    // the definitions of the compiled classes; what the extensions of this suite generated
                    // again beside them is on the classpath already
                    if (name.startsWith(packageName) && name.endsWith("$Definition")) {
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
            .start();
    }
}
