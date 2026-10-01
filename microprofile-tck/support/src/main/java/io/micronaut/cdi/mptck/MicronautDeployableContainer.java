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
    @Override public Class<MicronautContainerConfiguration> getConfigurationClass() { return MicronautContainerConfiguration.class; }
    @Override public void setup(MicronautContainerConfiguration config) { }
    @Override public void start() { }
    @Override public void stop() { }
    @Override public ProtocolDescription getDefaultProtocol() { return new ProtocolDescription("Local"); }
    @Override public ProtocolMetaData deploy(Archive<?> archive) throws DeploymentException {
        String component = System.getProperty("mp.tck.component");
        boolean imported = System.getProperty("mp.tck.mode").equals("imported");
        boolean reference = System.getProperty("mp.tck.mode").equals("reference");
        Path evidence = Path.of(System.getProperty("mp.tck.evidence"), String.format("%04d-%s", NEXT.incrementAndGet(), archive.getName()));
        CurrentDeployment.evidence = evidence;
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        CurrentDeployment.previousLoader = previous;
        try {
            Files.createDirectories(evidence);
            Files.writeString(evidence.resolve("archive.txt"), archive.toString(true));
            Set<String> classes = new TreeSet<>();
            collect(archive, classes);
            List<String> imports = new ArrayList<>();
            if ((imported && component.equals("config")) || Set.of("health", "context-propagation", "fault-tolerance", "jwt").contains(component)) {
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
                compiled = DeploymentCompiler.compile(classes, evidence, imports);
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
            org.eclipse.microprofile.config.spi.ConfigProviderResolver.setInstance(new io.smallrye.config.SmallRyeConfigProviderResolver());
            io.smallrye.config.Config.getOrCreate(loader);
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
            if (!imported) {
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
            CurrentDeployment.uri = java.net.URI.create("http://localhost:1");
            for (EndpointAdapter adapter : ServiceLoader.load(EndpointAdapter.class, previous)) {
                CurrentDeployment.endpoint = adapter.start(classes, loader);
            }
            Files.writeString(evidence.resolve("outcome.txt"), "STARTED\n");
            return new ProtocolMetaData();
        } catch (Throwable failure) {
            try {
                java.io.StringWriter trace = new java.io.StringWriter();
                failure.printStackTrace(new java.io.PrintWriter(trace));
                Files.writeString(evidence.resolve("failure.txt"), trace.toString());
            } catch (java.io.IOException writing) { failure.addSuppressed(writing); }
            cleanup();
            throw new DeploymentException("MicroProfile " + component + " deployment failed; evidence: " + evidence, failure);
        }
    }
    @Override public void undeploy(Archive<?> archive) { cleanup(); }
    @Override public void deploy(Descriptor descriptor) { throw new UnsupportedOperationException("Descriptor-only deployments are unsupported"); }
    @Override public void undeploy(Descriptor descriptor) { }
    private static void cleanup() {
        try { if (CurrentDeployment.endpoint != null) CurrentDeployment.endpoint.close(); }
        catch (Exception failure) { throw new IllegalStateException("Cannot stop TCK HTTP endpoint", failure); }
        finally {
            CurrentDeployment.endpoint = null;
            try { if (CurrentDeployment.container != null) CurrentDeployment.container.close(); }
            finally {
                CurrentDeployment.container = null;
                CurrentDeployment.context = null;
                CurrentDeployment.request = null;
                if (CurrentDeployment.loader != null) {
                    org.eclipse.microprofile.config.spi.ConfigProviderResolver.instance()
                        .releaseConfig(io.smallrye.config.Config.getOrCreate(CurrentDeployment.loader));
                    try { CurrentDeployment.loader.close(); } catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
                }
                CurrentDeployment.loader = null;
                if (CurrentDeployment.previousLoader != null) Thread.currentThread().setContextClassLoader(CurrentDeployment.previousLoader);
            }
        }
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
