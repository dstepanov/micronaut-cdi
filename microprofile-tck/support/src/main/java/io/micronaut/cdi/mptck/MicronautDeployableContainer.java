/* Licensed under the Apache License, Version 2.0. */
package io.micronaut.cdi.mptck;

import io.micronaut.cdi.se.MicronautSeContainerInitializer;
import org.jboss.arquillian.container.spi.client.container.*;
import org.jboss.arquillian.container.spi.client.protocol.ProtocolDescription;
import org.jboss.arquillian.container.spi.client.protocol.metadata.ProtocolMetaData;
import org.jboss.shrinkwrap.api.*;
import org.jboss.shrinkwrap.api.asset.*;
import org.jboss.shrinkwrap.descriptor.api.Descriptor;
import jakarta.enterprise.inject.spi.Extension;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** An archive is compiled and booted through the real Micronaut CDI SE initializer. */
public final class MicronautDeployableContainer implements DeployableContainer<MicronautContainerConfiguration> {
    private static final AtomicInteger NEXT = new AtomicInteger();
    private static final String FORK = ProcessHandle.current().pid() + "-" + System.currentTimeMillis();
    @Override public Class<MicronautContainerConfiguration> getConfigurationClass() { return MicronautContainerConfiguration.class; }
    @Override public void setup(MicronautContainerConfiguration config) { }
    @Override public void start() { }
    @Override public void stop() { }
    @Override public ProtocolDescription getDefaultProtocol() { return new ProtocolDescription("Local"); }
    @Override public ProtocolMetaData deploy(Archive<?> archive) throws DeploymentException {
        if (CurrentDeployment.container != null || CurrentDeployment.loader != null) {
            throw new DeploymentException("HARNESS: a deployment is already active; undeploy it before deploying another archive");
        }
        String component = System.getProperty("mp.tck.component");
        boolean imported = "imported".equals(System.getProperty("mp.tck.mode"));
        boolean lite = "lite".equals(System.getProperty("mp.tck.mode"));
        boolean reference = "reference".equals(System.getProperty("mp.tck.mode"));
        Path evidence = Path.of(System.getProperty("mp.tck.evidence"), String.format("%04d-fork-%s-%s", NEXT.incrementAndGet(), FORK, archive.getName()));
        CurrentDeployment.evidence = evidence;
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        CurrentDeployment.previousLoader = previous;
        try {
            Files.createDirectories(evidence);
            Files.writeString(evidence.resolve("archive.txt"), archive.toString(true));
            Set<String> classes = new TreeSet<>();
            collect(archive, classes);
            List<String> imports = new ArrayList<>();
            for (String name : System.getProperty("mp.tck.imports", "").split(",")) {
                if (!name.isBlank()) imports.add(name.trim());
            }
            if (((imported || lite) && component.equals("config")) || Set.of("health", "context-propagation", "fault-tolerance", "jwt").contains(component)) {
                imports.add("io.smallrye.config.inject.ConfigProducer");
            }
            if (component.equals("health")) imports.addAll(List.of("io.smallrye.health.SmallRyeHealthReporter", "io.smallrye.health.AsyncHealthCheckFactory"));
            if (imported && component.equals("graphql")) imports.addAll(List.of(
                "org.eclipse.microprofile.graphql.tck.apps.basic.api.ScalarTestApi",
                "org.eclipse.microprofile.graphql.tck.apps.superhero.api.HeroFinder"));
            Path compiled;
            if (reference) {
                compiled = Files.createDirectories(evidence.resolve("classes"));
            } else {
                compiled = DeploymentCompiler.compile(classes, evidence, imports,
                    archiveExtensions(archive, "META-INF/services/jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension"));
                GeneratedClasses.install(compiled, classes, previous);
            }
            var loader = new ArchiveClassLoader(archive, compiled.toUri().toURL(), previous);
            CurrentDeployment.loader = loader;
            Thread.currentThread().setContextClassLoader(loader);
            // Both containers are on the comparison classpath. Select the provider under test explicitly;
            // a higher-priority reference provider must not answer CDI.current() during a Micronaut run.
            jakarta.enterprise.inject.spi.CDI.setCDIProvider(reference
                ? (jakarta.enterprise.inject.spi.CDIProvider) loader.loadClass("org.jboss.weld.environment.se.WeldSEProvider").getConstructor().newInstance()
                : new io.micronaut.cdi.internal.runtime.MicronautCDIProvider());
            CurrentDeployment.previousConfigResolver = org.eclipse.microprofile.config.spi.ConfigProviderResolver.instance();
            CurrentDeployment.configResolver = new io.smallrye.config.SmallRyeConfigProviderResolver();
            org.eclipse.microprofile.config.spi.ConfigProviderResolver.setInstance(CurrentDeployment.configResolver);
            CurrentDeployment.config = io.smallrye.config.Config.getOrCreate(loader);
            List<Class<?>> beans = new ArrayList<>();
            for (String name : classes) {
                // Parent binaries preserve the test's class identity; generated definitions are deployment-local.
                String outer = name.contains("$") ? name.substring(0, name.indexOf('$')) : name;
                if (Files.exists(Path.of(System.getProperty("mp.tck.sources"), outer.replace('.', '/') + ".java"))) {
                    beans.add(loader.loadClass(name));
                }
            }
            for (String name : imports) beans.add(loader.loadClass(name));
            Class<?> bridge = reference ? null : loader.loadClass("io.micronaut.cdi.mptck.generated.DeploymentBridge");
            if (bridge != null) beans.add(bridge);
            if (reference) beans.add(ReferenceAnchor.class);
            jakarta.enterprise.inject.se.SeContainerInitializer base = reference
                ? (jakarta.enterprise.inject.se.SeContainerInitializer) loader.loadClass("org.jboss.weld.environment.se.Weld").getConstructor().newInstance()
                : new MicronautSeContainerInitializer();
            var initializer = base.setClassLoader(loader).disableDiscovery()
                .addBeanClasses(beans.toArray(Class<?>[]::new));
            List<Extension> extensions = new ArrayList<>();
            // Archive-owned extensions always run. Imported mode omits only the vendor extension, explicitly
            // exposing what can work with compile-time library imports and what still needs CDI Full.
            var services = loader.getResources("META-INF/services/jakarta.enterprise.inject.spi.Extension");
            while (services.hasMoreElements()) {
                try (var input = services.nextElement().openStream()) {
                    for (String line : new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).split("\n")) {
                        String name = line.split("#", 2)[0].trim();
                        if (!name.isEmpty()) extensions.add((Extension) loader.loadClass(name).getConstructor().newInstance());
                    }
                }
            }
            if (!imported && !lite) {
                String vendor = switch (component) {
                    case "config" -> "io.smallrye.config.inject.ConfigExtension";
                    case "fault-tolerance" -> "io.smallrye.faulttolerance.FaultToleranceExtension";
                    case "rest-client" -> "org.jboss.resteasy.microprofile.client.RestClientExtension";
                    case "context-propagation" -> "io.smallrye.context.inject.SmallryeContextCdiExtension";
                    case "reactive-messaging" -> "io.smallrye.reactive.messaging.providers.extension.ReactiveMessagingExtension";
                    case "jwt" -> null;
                    case "metrics-api", "metrics-rest", "metrics-optional" -> "io.smallrye.metrics.legacyapi.LegacyMetricsExtension";
                    case "telemetry-tracing", "telemetry-metrics", "telemetry-logs" -> "io.helidon.microprofile.telemetry.TelemetryCdiExtension";
                    default -> null;
                };
                if (vendor != null) extensions.add((Extension) loader.loadClass(vendor).getConstructor().newInstance());
            }
            initializer.addExtensions(extensions.toArray(Extension[]::new));
            CurrentDeployment.container = initializer.initialize();
            Files.writeString(evidence.resolve("container.txt"), CurrentDeployment.container.getBeanManager().getClass().getName()
                + "\nCDI.current(): " + jakarta.enterprise.inject.spi.CDI.current().getBeanManager().getClass().getName() + "\n");
            if (reference) {
                CurrentDeployment.request = CurrentDeployment.container.select(jakarta.enterprise.context.control.RequestContextController.class).get();
            } else {
                Object access = CurrentDeployment.container.select(bridge).get();
                CurrentDeployment.context = (io.micronaut.context.ApplicationContext) bridge.getField("context").get(access);
                CurrentDeployment.request = (jakarta.enterprise.context.control.RequestContextController) bridge.getField("request").get(access);
            }
            CurrentDeployment.uri = null;
            for (EndpointAdapter adapter : ServiceLoader.load(EndpointAdapter.class, previous)) {
                AutoCloseable endpoint = adapter.start(classes, loader);
                if (endpoint != null) CurrentDeployment.endpoints.add(endpoint);
            }
            Files.writeString(evidence.resolve("outcome.txt"), "STARTED\n");
            index(evidence, archive.getName(), "STARTED");
            return new ProtocolMetaData();
        } catch (Throwable failure) {
            try {
                java.io.StringWriter trace = new java.io.StringWriter();
                failure.printStackTrace(new java.io.PrintWriter(trace));
                Files.writeString(evidence.resolve("failure.txt"), trace.toString());
                Files.writeString(evidence.resolve("outcome.txt"), "FAILED\n");
                index(evidence, archive.getName(), "FAILED");
            } catch (java.io.IOException writing) { failure.addSuppressed(writing); }
            try { cleanup(); } catch (Throwable closing) { failure.addSuppressed(closing); }
            throw new DeploymentException("MicroProfile " + component + " deployment failed; evidence: " + evidence, failure);
        }
    }
    @Override public void undeploy(Archive<?> archive) { cleanup(); }
    @Override public void deploy(Descriptor descriptor) { throw new UnsupportedOperationException("Descriptor-only deployments are unsupported"); }
    @Override public void undeploy(Descriptor descriptor) { }
    static void cleanup() {
        // Stop accepting requests before destroying test-owned dependents and the container. Each
        // cleanup step still runs if a previous step failed; never retain a stale deployment globally.
        List<AutoCloseable> closing = new ArrayList<>(CurrentDeployment.endpoints);
        Collections.reverse(closing);
        CurrentDeployment.endpoints.clear();
        closing.add(MicronautTestEnricher::releaseAll);
        if (CurrentDeployment.container != null) closing.add(CurrentDeployment.container);
        if (CurrentDeployment.config != null && CurrentDeployment.configResolver != null) {
            var config = CurrentDeployment.config;
            var resolver = CurrentDeployment.configResolver;
            closing.add(() -> resolver.releaseConfig(config));
        }
        if (CurrentDeployment.loader != null) closing.add(CurrentDeployment.loader);
        Throwable failure = null;
        try {
            for (AutoCloseable resource : closing) {
                try { resource.close(); }
                catch (Throwable e) { if (failure == null) failure = e; else failure.addSuppressed(e); }
            }
        } finally {
            CurrentDeployment.container = null;
            CurrentDeployment.context = null;
            CurrentDeployment.request = null;
            CurrentDeployment.loader = null;
            CurrentDeployment.config = null;
            CurrentDeployment.configResolver = null;
            CurrentDeployment.uri = null;
            if (CurrentDeployment.previousConfigResolver != null) {
                org.eclipse.microprofile.config.spi.ConfigProviderResolver.setInstance(CurrentDeployment.previousConfigResolver);
                CurrentDeployment.previousConfigResolver = null;
            }
            if (CurrentDeployment.previousLoader != null) {
                Thread.currentThread().setContextClassLoader(CurrentDeployment.previousLoader);
                CurrentDeployment.previousLoader = null;
            }
        }
        if (failure != null) throw new IllegalStateException("Cannot clean up TCK deployment", failure);
    }
    private static void index(Path evidence, String archive, String outcome) throws java.io.IOException {
        // Suites use one serial fork at a time. Include the fork identity because NEXT restarts in each
        // JVM; otherwise different test classes overwrite a same-named archive's diagnostics.
        Files.writeString(evidence.getParent().resolve("deployment-index.tsv"),
            FORK + "\t" + archive + "\t" + outcome + "\t" + evidence.getFileName() + "\n",
            java.nio.charset.StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
    private static List<String> archiveExtensions(Archive<?> archive, String service) throws java.io.IOException {
        Set<String> names = new LinkedHashSet<>();
        for (var entry : archive.getContent().entrySet()) {
            var asset = entry.getValue().getAsset();
            if (asset instanceof ArchiveAsset nested) {
                names.addAll(archiveExtensions(nested.getArchive(), service));
            } else {
                String path = entry.getKey().get().substring(1);
                if (path.equals(service) || path.equals("WEB-INF/classes/" + service)) {
                    try (var input = asset.openStream()) {
                        for (String line : new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).split("\n")) {
                            String name = line.split("#", 2)[0].trim();
                            if (!name.isEmpty()) names.add(name);
                        }
                    }
                }
            }
        }
        return List.copyOf(names);
    }
    private static void collect(Archive<?> archive, Set<String> classes) {
        for (var entry : archive.getContent().entrySet()) {
            String path = entry.getKey().get().substring(1);
            if (entry.getValue().getAsset() instanceof ArchiveAsset nested) { collect(nested.getArchive(), classes); continue; }
            if (!path.endsWith(".class")) continue;
            if (path.startsWith("WEB-INF/classes/")) path = path.substring(16);
            String name = path.substring(0, path.length()-6).replace('/', '.');
            int inner = name.indexOf('$');
            classes.add(name);
            classes.add(inner < 0 ? name : name.substring(0, inner));
        }
    }
}
