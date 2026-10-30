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
import io.micronaut.cdi.test.CompiledClasses;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.DefaultBeanDefinitionsProvider;
import io.micronaut.inject.BeanDefinitionReference;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;

import java.util.ArrayList;
import java.util.List;

/**
 * Compiles a source in memory with the given build compatible extension alone, and starts a container with the beans
 * of this suite and the definitions that compilation generated: what the extension recorded is what the container
 * replays as it starts.
 */
final class ExtensionDeployment {

    private ExtensionDeployment() {
    }

    /**
     * Compiles the source with the extension and starts a container with what it compiled.
     *
     * @param extension The extension
     * @param className The name of the class the source declares
     * @param source    The source
     * @return The started context
     */
    static ApplicationContext start(Class<? extends BuildCompatibleExtension> extension, String className,
                                    String source) {
        CompiledClasses classes;
        try (JavaParser parser = ExtensionCompilation.of(extension)) {
            classes = CompiledClasses.of(parser.generate(className, source));
        }
        ClassLoader loader = classes.loader(ExtensionDeployment.class.getClassLoader());
        return ApplicationContext.builder()
            .classLoader(loader)
            .beanDefinitionsProvider(classLoader -> {
                List<BeanDefinitionReference<?>> references =
                    new ArrayList<>(new DefaultBeanDefinitionsProvider().provide(classLoader));
                for (String name : classes.names()) {
                    if (name.endsWith("$Definition")) {
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
