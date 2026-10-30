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
package io.micronaut.cdi.se;

import io.micronaut.cdi.internal.metadata.CdiBuiltIn;
import io.micronaut.cdi.internal.metadata.CdiProducer;
import io.micronaut.cdi.internal.metadata.CdiScope;
import io.micronaut.cdi.spi.PortableExtensions;
import io.micronaut.cdi.internal.runtime.CdiBeanContainer;
import io.micronaut.cdi.internal.runtime.UnselectedAlternative;
import io.micronaut.cdi.internal.runtime.CdiInterceptorEnablement;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.ApplicationContextBuilder;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.Internal;
import io.micronaut.inject.BeanDefinition;
import io.micronaut.inject.BeanType;
import io.micronaut.inject.ProxyBeanDefinition;
import jakarta.enterprise.inject.se.SeContainer;
import jakarta.enterprise.inject.se.SeContainerInitializer;
import jakarta.enterprise.inject.spi.DefinitionException;
import jakarta.enterprise.inject.spi.DeploymentException;
import jakarta.enterprise.inject.spi.Extension;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

/**
 * The SE bootstrap of the specification: what {@code SeContainerInitializer.newInstance()} finds through the
 * service loader, and what builds a container out of what was compiled.
 *
 * <p>A bean of this implementation is a bean by the time it has been compiled, so there is nothing for the
 * bootstrap to discover: {@code initialize()} starts a Micronaut application context over the compiled
 * definitions. Turning discovery off narrows that context to the classes and packages the program named — the
 * synthetic bean archive of the specification — with the container's own infrastructure staying in either
 * way.</p>
 *
 * <p>An alternative no priority selected was compiled behind the {@link UnselectedAlternative} condition;
 * {@code selectAlternatives} and {@code selectAlternativeStereotypes} name it in the properties that condition
 * reads, which is what enables it in the container being built.</p>
 *
 * <p>What belongs to CDI Full says so rather than pretending: a portable extension
 * ({@code addExtensions}) and a decorator ({@code enableDecorators}) have no compile-time counterpart here.
 * A build compatible extension is found through the service loader while the application compiles, so handing
 * one to the bootstrap at runtime is refused the same way. {@code enableInterceptors} accepts the interceptor
 * classes the Jakarta Interceptors processor compiled and enables the ones that declare no priority, through the
 * property {@link CdiInterceptorEnablement} reads; interception itself was woven at compile time.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class MicronautSeContainerInitializer extends SeContainerInitializer {

    private static volatile @Nullable Set<String> restrictedClasspath;

    private final Set<String> beanClasses = new LinkedHashSet<>();
    private final Set<String> packages = new LinkedHashSet<>();
    private final Set<String> recursivePackages = new LinkedHashSet<>();
    private final Set<String> selectedAlternatives = new LinkedHashSet<>();
    private final Set<String> selectedAlternativeStereotypes = new LinkedHashSet<>();
    private final Set<String> enabledInterceptors = new LinkedHashSet<>();
    private final Map<String, Object> properties = new LinkedHashMap<>();

    private final List<Extension> extensionInstances = new ArrayList<>();
    private final List<Class<? extends Extension>> extensionClasses = new ArrayList<>();

    private boolean discoveryDisabled;
    private ClassLoader classLoader = Thread.currentThread().getContextClassLoader();

    /**
     * Restricts what a bootstrap on this JVM may discover, in place of the classpath it cannot have: what a
     * harness that runs many logical deployments in one JVM — the kit's — sets around each. The launched JVM
     * the specification's SE tests are written for holds only the deployment's archive; this stands in for
     * that. Setting {@code null} puts the whole classpath back.
     *
     * @param classNames The application classes of the deployment, or {@code null} for no restriction
     */
    @Internal
    public static void restrictClasspath(@Nullable Set<String> classNames) {
        restrictedClasspath = classNames == null ? null : Set.copyOf(classNames);
    }

    @Override
    public SeContainerInitializer addBeanClasses(Class<?>... classes) {
        for (Class<?> beanClass : classes) {
            beanClasses.add(beanClass.getName());
        }
        return this;
    }

    @Override
    public SeContainerInitializer addPackages(Class<?>... packageClasses) {
        return addPackages(false, packageClasses);
    }

    @Override
    public SeContainerInitializer addPackages(boolean scanRecursively, Class<?>... packageClasses) {
        for (Class<?> packageClass : packageClasses) {
            (scanRecursively ? recursivePackages : packages).add(packageClass.getPackageName());
        }
        return this;
    }

    @Override
    public SeContainerInitializer addPackages(Package... names) {
        return addPackages(false, names);
    }

    @Override
    public SeContainerInitializer addPackages(boolean scanRecursively, Package... names) {
        for (Package name : names) {
            (scanRecursively ? recursivePackages : packages).add(name.getName());
        }
        return this;
    }

    @Override
    public SeContainerInitializer addExtensions(Extension... extensions) {
        // run as the container starts, by the optional module that reads classes; without it the bootstrap
        // fails, naming the module
        extensionInstances.addAll(List.of(extensions));
        return this;
    }

    @SafeVarargs
    @Override
    public final SeContainerInitializer addExtensions(Class<? extends Extension>... extensions) {
        extensionClasses.addAll(List.of(extensions));
        return this;
    }

    @Override
    public SeContainerInitializer enableInterceptors(Class<?>... interceptorClasses) {
        // interception was woven where the intercepted bean was compiled, and the interceptor classes are already
        // beans of the container. What is left for the bootstrap is the enablement: an interceptor bound by an
        // interceptor binding that declares no priority takes part only where it is named here
        for (Class<?> interceptorClass : interceptorClasses) {
            enabledInterceptors.add(interceptorClass.getName());
        }
        return this;
    }

    @Override
    public SeContainerInitializer enableDecorators(Class<?>... decoratorClasses) {
        throw new UnsupportedOperationException("Decorators belong to CDI Full");
    }

    @Override
    public SeContainerInitializer selectAlternatives(Class<?>... alternativeClasses) {
        for (Class<?> alternativeClass : alternativeClasses) {
            selectedAlternatives.add(alternativeClass.getName());
        }
        return this;
    }

    @SafeVarargs
    @Override
    public final SeContainerInitializer selectAlternativeStereotypes(
        Class<? extends Annotation>... alternativeStereotypeClasses) {
        for (Class<? extends Annotation> stereotype : alternativeStereotypeClasses) {
            selectedAlternativeStereotypes.add(stereotype.getName());
        }
        return this;
    }

    @Override
    public SeContainerInitializer addProperty(String key, Object value) {
        properties.put(key, value);
        return this;
    }

    @Override
    public SeContainerInitializer setProperties(Map<String, Object> properties) {
        this.properties.clear();
        this.properties.putAll(properties);
        return this;
    }

    @Override
    public SeContainerInitializer disableDiscovery() {
        discoveryDisabled = true;
        return this;
    }

    @Override
    public SeContainerInitializer setClassLoader(ClassLoader classLoader) {
        this.classLoader = classLoader;
        return this;
    }

    @Override
    public SeContainer initialize() {
        ApplicationContextBuilder builder = ApplicationContext.builder(classLoader)
            .properties(properties);
        if (!selectedAlternatives.isEmpty()) {
            builder.properties(Map.of(UnselectedAlternative.SELECTED_CLASSES, joined(selectedAlternatives)));
        }
        if (!selectedAlternativeStereotypes.isEmpty()) {
            builder.properties(Map.of(UnselectedAlternative.SELECTED_STEREOTYPES,
                joined(selectedAlternativeStereotypes)));
        }
        if (!enabledInterceptors.isEmpty()) {
            builder.properties(Map.of(CdiInterceptorEnablement.ENABLED_CLASSES, joined(enabledInterceptors)));
        }
        Set<String> classpath = restrictedClasspath;
        if (discoveryDisabled) {
            builder.beansPredicate(bean -> belongsToSyntheticArchive(bean)
                && (classpath == null || onClasspath(bean, classpath)));
        } else if (classpath != null) {
            builder.beansPredicate(bean -> onClasspath(bean, classpath));
        }
        // what the portable extensions of this bootstrap are, for the container to run as it starts
        builder.singletons(new PortableExtensions.Request(List.copyOf(extensionInstances),
            List.copyOf(extensionClasses), classLoader,
            name -> classpath == null || classpath.contains(outerClassOf(name))));
        ApplicationContext context = builder.build();
        try {
            return new MicronautSeContainer(context.start());
        } catch (RuntimeException | Error e) {
            // whatever the start failed with, the context is closed and its container is not left as the one
            // CDI.current() resolves to; what an extension said is reported as it said it
            RuntimeException said = e instanceof RuntimeException runtime ? whatAnExtensionSaid(runtime) : null;
            Throwable reported = said != null ? said : e;
            try {
                context.close();
            } catch (RuntimeException | Error closing) {
                if (closing != reported) {
                    reported.addSuppressed(closing);
                }
            } finally {
                CdiBeanContainer.failedToStart(context);
            }
            if (said != null) {
                throw said;
            }
            throw e;
        }
    }

    /**
     * What a portable extension, or the container on its behalf, failed the bootstrap with, which Micronaut
     * wraps as the failure to create the bean that ran the extensions.
     */
    private static @Nullable RuntimeException whatAnExtensionSaid(Throwable failure) {
        // each cause once: two exceptions may name each other as their cause
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cause = failure; cause != null && seen.add(cause); cause = cause.getCause()) {
            if (cause instanceof DefinitionException
                || cause instanceof DeploymentException
                || cause instanceof UnsupportedOperationException) {
                return (RuntimeException) cause;
            }
        }
        return null;
    }

    private static String joined(Set<String> names) {
        StringJoiner joiner = new StringJoiner(",");
        names.forEach(joiner::add);
        return joiner.toString();
    }

    /**
     * Whether a bean belongs to the synthetic archive of a bootstrap that turned discovery off: one of the
     * classes or packages the program named, or the infrastructure of the container itself, which every
     * container has.
     */
    private boolean belongsToSyntheticArchive(BeanType<?> bean) {
        if (isInfrastructure(bean)) {
            return true;
        }
        String producer = producerOf(bean);
        if (producer != null) {
            // a bean produced by a member of a class belongs to the archive its producer is in, whatever type it
            // produces - and so does the client proxy of one in a normal scope, which declares itself and is of
            // the produced type
            return isSelected(producer);
        }
        if (bean instanceof BeanDefinition<?> definition) {
            // a bean produced by a member of an application class belongs to the archive its producer is in,
            // whatever type it produces
            // the client proxy of a bean declares itself; it is in the archive its bean class is in
            Class<?> declaring = declaringClassOf(definition);
            if (declaring != null) {
                boolean selected = isSelected(declaring.getName());
                if (selected && isProduced(definition, declaring)) {
                    // the producer's class is in the archive, and so is what it produces: a String, say
                    return true;
                }
                if (!selected) {
                    return false;
                }
            }
        }
        return isSelected(classOf(bean).getName());
    }

    private static boolean onClasspath(BeanType<?> bean, Set<String> classpath) {
        if (isInfrastructure(bean)) {
            return true;
        }
        String producer = producerOf(bean);
        if (producer != null && classpath.contains(outerClassOf(producer))) {
            // what a producer produces, its client proxy included, is where the producer is
            return true;
        }
        if (bean instanceof BeanDefinition<?> definition) {
            // the client proxy of a bean declares itself; it is in the archive its bean class is in
            Class<?> declaring = declaringClassOf(definition);
            if (declaring != null) {
                boolean listed = classpath.contains(outerClassOf(declaring.getName()));
                if (listed && isProduced(definition, declaring)) {
                    return true;
                }
                if (!listed) {
                    return false;
                }
            }
        }
        return classpath.contains(outerClassOf(classOf(bean).getName()));
    }

    /**
     * The class a definition is declared by, as far as the archive it is in goes: the client proxy of a bean
     * declares itself, and is in the archive its bean class is in.
     */
    private static @Nullable Class<?> declaringClassOf(BeanDefinition<?> definition) {
        return definition instanceof ProxyBeanDefinition<?> proxied
            ? proxied.getTargetType() : definition.getDeclaringType().orElse(null);
    }

    /**
     * The class of a bean, the class a client proxy stands in front of for a proxy.
     */
    private static Class<?> classOf(BeanType<?> bean) {
        return bean instanceof ProxyBeanDefinition<?> proxy ? proxy.getTargetType() : bean.getBeanType();
    }

    /**
     * The class that declares the producer of a produced bean, as the processor recorded it, or {@code null} for
     * a bean that is a class itself.
     */
    private static @Nullable String producerOf(BeanType<?> bean) {
        if (!(bean instanceof BeanDefinition<?> definition)) {
            return null;
        }
        return definition.getAnnotationMetadata()
            .stringValue(CdiProducer.class, "declaringType").orElse(null);
    }

    /**
     * Whether the bean is produced by a member of another class - a producer method or field - rather than
     * being a class itself.
     */
    private static boolean isProduced(BeanDefinition<?> definition, Class<?> declaring) {
        return !(definition instanceof ProxyBeanDefinition<?>) && declaring != definition.getBeanType();
    }

    /**
     * Whether a bean is infrastructure every container has rather than a bean of an archive. A built-in bean of the
     * container is. A bean of the specification - one the processor recorded a scope or a producer of - is not: it
     * is a bean of the archive it was selected into whatever its package, even under {@code io.micronaut}. A bean
     * with nothing of the specification recorded - a plain {@code jakarta.inject} singleton of the application as
     * much as one of Micronaut's own - is infrastructure only where its class is the framework's, under
     * {@code io.micronaut} or {@code jakarta}: missing metadata alone does not make a bean the framework's.
     */
    private static boolean isInfrastructure(BeanType<?> bean) {
        AnnotationMetadata metadata = bean.getAnnotationMetadata();
        if (metadata.hasAnnotation(CdiBuiltIn.class)) {
            return true;
        }
        if (metadata.hasAnnotation(CdiScope.class) || producerOf(bean) != null) {
            return false;
        }
        String name = classOf(bean).getName();
        return name.startsWith("io.micronaut.") || name.startsWith("jakarta.");
    }

    private boolean isSelected(String className) {
        // an extension the program handed over is part of the container, and a bean of it
        for (Extension extension : extensionInstances) {
            if (extension.getClass().getName().equals(className)) {
                return true;
            }
        }
        for (Class<? extends Extension> extension : extensionClasses) {
            if (extension.getName().equals(className)) {
                return true;
            }
        }
        String outer = outerClassOf(className);
        if (beanClasses.contains(outer) || beanClasses.contains(className)) {
            return true;
        }
        int dot = className.lastIndexOf('.');
        String packageName = dot < 0 ? "" : className.substring(0, dot);
        if (packages.contains(packageName)) {
            return true;
        }
        for (String recursive : recursivePackages) {
            if (packageName.equals(recursive) || packageName.startsWith(recursive + ".")) {
                return true;
            }
        }
        return false;
    }

    private static String outerClassOf(String className) {
        // a generated definition mangles the simple name with a leading dollar ($Host$Definition$Intercepted),
        // which names the class Host in this package
        int dot = className.lastIndexOf('.');
        String packagePrefix = className.substring(0, dot + 1);
        String simple = className.substring(dot + 1);
        if (simple.startsWith("$")) {
            simple = simple.substring(1);
        }
        int inner = simple.indexOf('$');
        return packagePrefix + (inner < 0 ? simple : simple.substring(0, inner));
    }
}
