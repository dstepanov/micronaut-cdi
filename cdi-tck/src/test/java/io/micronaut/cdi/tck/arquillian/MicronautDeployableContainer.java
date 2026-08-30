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
package io.micronaut.cdi.tck.arquillian;

import io.micronaut.context.ApplicationContext;
import io.micronaut.inject.ProxyBeanDefinition;
import org.jboss.arquillian.container.spi.client.container.DeployableContainer;
import org.jboss.arquillian.container.spi.client.container.DeploymentException;
import org.jboss.arquillian.container.spi.client.container.LifecycleException;
import org.jboss.arquillian.container.spi.client.protocol.ProtocolDescription;
import org.jboss.arquillian.container.spi.client.protocol.metadata.ProtocolMetaData;
import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.api.ArchivePath;
import org.jboss.shrinkwrap.descriptor.api.Descriptor;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The container the kit's test classes drive: a deployment is a Micronaut application context narrowed to the
 * classes of the archive.
 *
 * <p>The kit builds an archive per test class — the beans that test is about — deploys it, and asserts against a
 * container holding those beans and nothing else. Everything here is compiled into one classpath instead, and
 * what was compiled is already decided; what deploying an archive can still decide is which of those compiled
 * beans the container holds. Micronaut takes a predicate over the beans it will load, and the predicate is the
 * archive: a bean of the kit's namespace is held only if the archive names its class.</p>
 *
 * <p>Everything outside the kit's namespace is held regardless, because that is the infrastructure of the
 * container itself — the scopes, the bean manager, the observer registry.</p>
 */
public final class MicronautDeployableContainer implements DeployableContainer<MicronautContainerConfiguration> {

    private static final String CLASS_SUFFIX = ".class";

    @Override
    public Class<MicronautContainerConfiguration> getConfigurationClass() {
        return MicronautContainerConfiguration.class;
    }

    @Override
    public void setup(MicronautContainerConfiguration configuration) {
        // nothing to set up
    }

    @Override
    public void start() throws LifecycleException {
        // a container exists per deployment rather than per suite
    }

    @Override
    public void stop() throws LifecycleException {
        // nothing to stop
    }

    @Override
    public ProtocolDescription getDefaultProtocol() {
        // the tests run in this very JVM, which Arquillian calls the local protocol
        return new ProtocolDescription("Local");
    }

    @Override
    public ProtocolMetaData deploy(Archive<?> archive) throws DeploymentException {
        Set<String> classes = classesOf(archive);
        if (org.jboss.arquillian.container.se.api.ClassPath.isRepresentedBy(archive)) {
            // an SE bootstrap test: the kit writes it for a freshly launched JVM holding only the archive, and
            // the test builds its own containers with SeContainerInitializer. Nothing is deployed here beyond
            // standing in for that classpath: the bootstrap is told what the launched JVM would have held, and
            // the context started for the enricher holds the infrastructure alone, so that no scenario bean of
            // the archive observes this deployment's own startup
            io.micronaut.cdi.se.MicronautSeContainerInitializer.restrictClasspath(classes);
            try {
                ApplicationContext context = ApplicationContext.builder()
                    .beansPredicate(bean -> !bean.getBeanType().getName().startsWith("org.jboss.cdi.tck."))
                    .build()
                    .start();
                CurrentDeployment.started(context);
                return new ProtocolMetaData();
            } catch (RuntimeException e) {
                throw new DeploymentException("The deployment could not be started", e);
            }
        }
        DeployableSources sources = DeployableSources.configured();
        java.util.List<String> extensions = extensionsOf(archive);
        if (!extensions.isEmpty() && !hasBeansXml(archive)) {
            // an archive without a beans.xml is not a bean archive (section 2.1.1): nothing in it is
            // discovered on its own, and only what the extensions say — scanned classes, synthetic beans —
            // exists. The deployment still compiles, so the extensions run
            return deployCompiled(archive, classes, sources, extensions, false);
        }
        if (!extensions.isEmpty()) {
            // a deployment with build compatible extensions of its own: its classes are compiled now, with
            // those extensions — and only those — having their say, the way section 2.10 has extensions run
            // while the deployment is built
            return deployCompiled(archive, classes, sources, extensions);
        }
        if (classes.stream().anyMatch(sources::isBroken)
            || classes.stream().anyMatch(sources::declaresFilteredNestedBeans)) {
            // a deployment the kit expects the container to reject, or one whose beans are nested inside the
            // test class the shared compilation filtered out: deployment is compilation here, so the archive's
            // classes are compiled now, alone, and what the compiler rejects is what is reported
            return deployCompiled(archive, classes, sources);
        }
        try {
            ApplicationContext context = ApplicationContext.builder()
                .beansPredicate(bean -> {
                    // a bean produced by a member of the kit's classes belongs to the deployment its producer
                    // is in, whatever type it produces: a produced String[] carries no package of its own
                    if (bean instanceof io.micronaut.inject.BeanDefinition<?> definition) {
                        java.util.Optional<Class<?>> declaring = definition.getDeclaringType();
                        if (declaring.isPresent()
                            && declaring.get().getName().startsWith("org.jboss.cdi.tck.")
                            && !classes.contains(outerClassOf(declaring.get().getName()))) {
                            return false;
                        }
                    }
                    Class<?> type = bean instanceof ProxyBeanDefinition<?> proxy
                        ? proxy.getTargetType() : bean.getBeanType();
                    String name = type.getName();
                    if (!name.startsWith("org.jboss.cdi.tck.")) {
                        // the infrastructure of the container itself, which every deployment has
                        return true;
                    }
                    return classes.contains(outerClassOf(name));
                })
                .build()
                .start();
            try {
                DeploymentValidator.validate(context, classes);
            } catch (jakarta.enterprise.inject.spi.DeploymentException e) {
                context.close();
                throw new DeploymentException("The deployment was rejected as it validated", e);
            }
            CurrentDeployment.started(context);
            return new ProtocolMetaData();
        } catch (RuntimeException e) {
            throw new DeploymentException("The deployment could not be started", e);
        }
    }

    /**
     * Deploys an archive by compiling its classes, reporting what the compiler and the started container
     * reject the way the specification names deployment problems.
     */
    private ProtocolMetaData deployCompiled(Archive<?> archive, Set<String> classes, DeployableSources sources)
        throws DeploymentException {
        return deployCompiled(archive, classes, sources, java.util.List.of());
    }

    private ProtocolMetaData deployCompiled(Archive<?> archive, Set<String> classes, DeployableSources sources,
                                            java.util.List<String> extensionClassNames)
        throws DeploymentException {
        return deployCompiled(archive, classes, sources, extensionClassNames, true);
    }

    private ProtocolMetaData deployCompiled(Archive<?> archive, Set<String> classes, DeployableSources sources,
                                            java.util.List<String> extensionClassNames, boolean beanArchive)
        throws DeploymentException {
        java.util.Map<String, String> compilable = new java.util.LinkedHashMap<>();
        for (String name : classes) {
            String source = sources.sourceOf(name);
            if (source == null && sources.declaresFilteredNestedBeans(name)) {
                // a test class whose nested members are the deployment's beans compiles as it is: everything
                // it reaches for is on the suite's own classpath
                source = sources.rawSourceOf(name);
            }
            if (source != null) {
                compilable.put(name, source);
            }
        }
        java.util.List<jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension> extensions =
            new java.util.ArrayList<>(extensionClassNames.size());
        for (String extensionClassName : extensionClassNames) {
            try {
                extensions.add((jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension)
                    Class.forName(extensionClassName, true, getClass().getClassLoader())
                        .getDeclaredConstructor().newInstance());
            } catch (ReflectiveOperationException e) {
                throw new DeploymentException("The extension " + extensionClassName + " could not be created", e);
            }
        }
        Iterable<? extends javax.tools.JavaFileObject> generated;
        try {
            if (!extensions.isEmpty()) {
                io.micronaut.cdi.processor.extension.BuildCompatibleExtensionVisitor
                    .overrideExtensions(extensions);
            }
            generated = DeploymentCompiler.compile(compilable);
        } catch (jakarta.enterprise.inject.spi.DefinitionException
                 | jakarta.enterprise.inject.spi.DeploymentException e) {
            throw new DeploymentException("The deployment was rejected as it compiled", e);
        } catch (RuntimeException e) {
            // what an extension threw travels wrapped in the compiler's own reporting: the problem the
            // specification names is in the cause chain
            for (Throwable cause = e; cause != null && cause != cause.getCause(); cause = cause.getCause()) {
                if (cause instanceof jakarta.enterprise.inject.spi.DefinitionException
                    || cause instanceof jakarta.enterprise.inject.spi.DeploymentException) {
                    throw new DeploymentException("The deployment was rejected as it compiled", cause);
                }
            }
            throw e;
        } finally {
            if (!extensions.isEmpty()) {
                io.micronaut.cdi.processor.extension.BuildCompatibleExtensionVisitor.overrideExtensions(null);
            }
        }
        if (!beanArchive) {
            // only what the extensions scanned is a bean of an archive that is not a bean archive
            Set<String> scanned = new HashSet<>();
            for (String scannedClass
                : io.micronaut.cdi.processor.extension.BuildCompatibleExtensionVisitor.lastScannedClasses()) {
                scanned.add(outerClassOf(scannedClass));
            }
            classes = scanned;
        }
        Set<String> deployedBeans = classes;
        DeploymentClassLoader loader = new DeploymentClassLoader(generated, getClass().getClassLoader());
        if (!extensionClassNames.isEmpty()) {
            // the deployment's own service entry, so that the synthesis of section 2.10.5 — run as the
            // container starts, through the deployment's loader — sees the same extensions
            loader.addResource(
                "META-INF/services/jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension",
                String.join("\n", extensionClassNames).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        try {
            if (!extensions.isEmpty()) {
                // the same instances that ran the earlier phases run the synthesis and validation of section
                // 2.10: every extension has one instance across all of its phases
                io.micronaut.cdi.runtime.extension.SynthesisRunner.overrideExtensions(extensions);
            }
            ApplicationContext context = ApplicationContext.builder()
                .classLoader(loader)
                .beansPredicate(bean -> {
                    if (bean instanceof io.micronaut.inject.BeanDefinition<?> definition) {
                        java.util.Optional<Class<?>> declaring = definition.getDeclaringType();
                        if (declaring.isPresent()
                            && declaring.get().getName().startsWith("org.jboss.cdi.tck.")
                            && !deployedBeans.contains(outerClassOf(declaring.get().getName()))) {
                            return false;
                        }
                    }
                    Class<?> type = bean instanceof ProxyBeanDefinition<?> proxy
                        ? proxy.getTargetType() : bean.getBeanType();
                    String name = type.getName();
                    if (!name.startsWith("org.jboss.cdi.tck.")) {
                        return true;
                    }
                    return deployedBeans.contains(outerClassOf(name));
                })
                .beanDefinitionsProvider(classLoader -> referencesOf(loader, classLoader))
                .build()
                .start();
            try {
                DeploymentValidator.validate(context, deployedBeans);
            } catch (jakarta.enterprise.inject.spi.DeploymentException e) {
                context.close();
                throw new DeploymentException("The deployment was rejected as it validated", e);
            }
            CurrentDeployment.started(context);
            return new ProtocolMetaData();
        } catch (RuntimeException e) {
            for (Throwable cause = e; cause != null && cause != cause.getCause(); cause = cause.getCause()) {
                if (cause instanceof jakarta.enterprise.inject.spi.DefinitionException
                    || cause instanceof jakarta.enterprise.inject.spi.DeploymentException) {
                    throw new DeploymentException("The deployment could not be started", cause);
                }
            }
            throw new DeploymentException("The deployment could not be started",
                new jakarta.enterprise.inject.spi.DeploymentException(
                    "The deployment could not be started", e));
        } finally {
            if (!extensions.isEmpty()) {
                io.micronaut.cdi.runtime.extension.SynthesisRunner.overrideExtensions(null);
            }
        }
    }

    /**
     * The definitions of a compiled deployment: what the compilation just generated, over what the classpath
     * already had, with the classpath's copy of a definition the compilation regenerated dropped.
     */
    private static java.util.List<io.micronaut.inject.BeanDefinitionReference<?>> referencesOf(
        DeploymentClassLoader loader, ClassLoader contextClassLoader) {
        java.util.Map<String, io.micronaut.inject.BeanDefinitionReference<?>> byName =
            new java.util.LinkedHashMap<>();
        for (io.micronaut.inject.BeanDefinitionReference<?> reference
            : new io.micronaut.context.DefaultBeanDefinitionsProvider().provide(contextClassLoader)) {
            byName.put(reference.getBeanDefinitionName(), reference);
        }
        for (String name : loader.compiledClassNames()) {
            if (!name.endsWith("$Definition") && !name.endsWith("$Definition$Reference")) {
                continue;
            }
            try {
                Class<?> definitionClass = loader.loadClass(name);
                if (io.micronaut.inject.BeanDefinitionReference.class.isAssignableFrom(definitionClass)) {
                    io.micronaut.inject.BeanDefinitionReference<?> reference =
                        (io.micronaut.inject.BeanDefinitionReference<?>)
                            definitionClass.getDeclaredConstructor().newInstance();
                    byName.put(reference.getBeanDefinitionName(), reference);
                }
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("The compiled definition " + name + " could not be loaded", e);
            }
        }
        return java.util.List.copyOf(byName.values());
    }

    @Override
    public void undeploy(Archive<?> archive) throws DeploymentException {
        io.micronaut.cdi.se.MicronautSeContainerInitializer.restrictClasspath(null);
        try {
            CurrentDeployment.context().close();
        } catch (IllegalStateException e) {
            // the deployment never came up, and there is nothing to close
        } finally {
            CurrentDeployment.stopped();
        }
    }

    @Override
    public void deploy(Descriptor descriptor) throws DeploymentException {
        throw new UnsupportedOperationException("A descriptor is not a deployment here");
    }

    @Override
    public void undeploy(Descriptor descriptor) throws DeploymentException {
        throw new UnsupportedOperationException("A descriptor is not a deployment here");
    }

    /**
     * The classes the archive names, wherever in it they are named: the root of a Java archive, or the classes
     * directory of a web archive.
     */
    private static boolean hasBeansXml(Archive<?> archive) {
        for (ArchivePath path : archive.getContent().keySet()) {
            String name = path.get();
            // the archive's own descriptor, not one a support library carries
            if (name.equals("/WEB-INF/beans.xml") || name.equals("/META-INF/beans.xml")
                || name.equals("/WEB-INF/classes/META-INF/beans.xml")) {
                return true;
            }
        }
        return false;
    }

    /**
     * The build compatible extensions an archive names in its service entries, in the order they are named.
     */
    private static java.util.List<String> extensionsOf(Archive<?> archive) {
        java.util.List<String> extensions = new java.util.ArrayList<>(1);
        collectExtensions(archive, extensions);
        return extensions;
    }

    private static void collectExtensions(Archive<?> archive, java.util.List<String> extensions) {
        for (Map.Entry<ArchivePath, org.jboss.shrinkwrap.api.Node> entry : archive.getContent().entrySet()) {
            String path = entry.getKey().get();
            org.jboss.shrinkwrap.api.asset.Asset asset = entry.getValue().getAsset();
            if (path.endsWith(".jar") && asset instanceof org.jboss.shrinkwrap.api.asset.ArchiveAsset nested) {
                collectExtensions(nested.getArchive(), extensions);
                continue;
            }
            if (asset == null || !path.endsWith(
                "services/jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension")) {
                continue;
            }
            try (java.io.InputStream in = asset.openStream()) {
                for (String line : new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                    .split("\n")) {
                    String name = line.trim();
                    if (!name.isEmpty() && !name.startsWith("#")) {
                        extensions.add(name);
                    }
                }
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException("The extension service entry could not be read", e);
            }
        }
    }

    private static Set<String> classesOf(Archive<?> archive) {
        Set<String> classes = new HashSet<>();
        collectClasses(archive, classes);
        return classes;
    }

    private static void collectClasses(Archive<?> archive, Set<String> classes) {
        for (Map.Entry<ArchivePath, org.jboss.shrinkwrap.api.Node> entry : archive.getContent().entrySet()) {
            String path = entry.getKey().get();
            org.jboss.shrinkwrap.api.asset.Asset asset = entry.getValue().getAsset();
            if (path.endsWith(".jar") && asset instanceof org.jboss.shrinkwrap.api.asset.ArchiveAsset nested) {
                // a library of the deployment is part of the deployment: the kit packs beans into jars under
                // WEB-INF/lib as often as it puts them among the classes
                collectClasses(nested.getArchive(), classes);
                continue;
            }
            if (!path.endsWith(CLASS_SUFFIX)) {
                continue;
            }
            String name = path.substring(1, path.length() - CLASS_SUFFIX.length());
            if (name.startsWith("WEB-INF/classes/")) {
                name = name.substring("WEB-INF/classes/".length());
            }
            classes.add(outerClassOf(name.replace('/', '.')));
        }
    }

    /**
     * The class an inner class belongs to, so that an archive naming either holds both: the kit's builders add
     * whole classes, and a scenario's inner class is part of its scenario.
     */
    private static String outerClassOf(String name) {
        // a generated definition mangles the simple name with a leading dollar
        // ($Host$Definition$Intercepted), which names the class Host in this package
        int dot = name.lastIndexOf('.');
        String packagePrefix = name.substring(0, dot + 1);
        String simple = name.substring(dot + 1);
        if (simple.startsWith("$")) {
            simple = simple.substring(1);
        }
        int inner = simple.indexOf('$');
        return packagePrefix + (inner < 0 ? simple : simple.substring(0, inner));
    }
}
