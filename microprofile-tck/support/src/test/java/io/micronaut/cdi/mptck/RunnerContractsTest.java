/* Licensed under the Apache License, Version 2.0. */
package io.micronaut.cdi.mptck;

import io.micronaut.cdi.mptck.fixtures.HarnessFixtures;
import jakarta.enterprise.inject.spi.AnnotatedField;
import jakarta.enterprise.inject.spi.AnnotatedParameter;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.testng.annotations.Test;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicInteger;
import static org.testng.Assert.*;

public class RunnerContractsTest {
    @Test public void producerReceivesUnmanagedMemberAndDependentsAreReleased() throws Exception {
        var previous = Thread.currentThread().getContextClassLoader();
        var previousResolver = org.eclipse.microprofile.config.spi.ConfigProviderResolver.instance();
        var container = new MicronautDeployableContainer();
        var archive = ShrinkWrap.create(JavaArchive.class, "enrichment.jar").addClasses(HarnessFixtures.class, HarnessFixtures.Factory.class,
            HarnessFixtures.Owned.class, HarnessFixtures.Unmanaged.class, HarnessFixtures.Missing.class, HarnessFixtures.RequestBean.class, HarnessFixtures.ArchiveBuild.class)
            .addAsResource(new org.jboss.shrinkwrap.api.asset.StringAsset(HarnessFixtures.ArchiveBuild.class.getName() + "\n"),
                "META-INF/services/jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension");
        HarnessFixtures.POINTS.clear(); HarnessFixtures.disposed = 0; HarnessFixtures.discoveries = 0;
        try {
            container.deploy(archive);
            assertEquals(HarnessFixtures.discoveries, 1, "Archive-owned build-compatible extension must execute during deployment compilation");
            DeploymentCompiler.compile(java.util.Set.of(HarnessFixtures.class.getName()),
                CurrentDeployment.evidence.resolve("extension-reset-check"), java.util.List.of(), java.util.List.of());
            assertEquals(HarnessFixtures.discoveries, 1, "A deployment's extension override must not leak to the next compilation");
            assertThrows(IllegalStateException.class, () -> MicronautTestEnricher.resource(java.net.URI.class));
            String previousUrl = System.getProperty("test.url");
            int destroyed = HarnessFixtures.RequestBean.DESTROYED.get();
            try (var endpoint = new HttpEndpoint(CurrentDeployment.loader, exchange -> {
                assertSame(Thread.currentThread().getContextClassLoader(), CurrentDeployment.loader);
                HttpEndpoint.respond(exchange, 200, "text/plain", String.valueOf(CurrentDeployment.bean(HarnessFixtures.RequestBean.class).id()));
            })) {
                var client = java.net.http.HttpClient.newHttpClient();
                var request = java.net.http.HttpRequest.newBuilder(CurrentDeployment.uri).build();
                var first = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
                var second = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
                assertEquals(first.statusCode(), 200); assertEquals(second.statusCode(), 200);
                assertNotEquals(first.body(), second.body(), "HTTP requests must have distinct contextual instances");
            }
            assertEquals(HarnessFixtures.RequestBean.DESTROYED.get(), destroyed + 2);
            assertEquals(System.getProperty("test.url"), previousUrl);
            var test = new HarnessFixtures.Unmanaged();
            var enricher = new MicronautTestEnricher();
            enricher.enrich(test);
            assertEquals(test.field, "field");
            assertEquals(test.initialized, "initialize");
            assertEquals(test.privateInitializerCalls, 1);
            assertEquals(test.overriddenInitializerCalls, 0);
            assertEquals(test.deferred.get(), "deferred");
            var fieldPoint = HarnessFixtures.POINTS.stream().filter(point -> point.getMember() instanceof java.lang.reflect.Field
                && point.getMember().getName().equals("field")).findFirst().orElseThrow();
            assertEquals(fieldPoint.getMember(), HarnessFixtures.Unmanaged.class.getField("field"));
            assertEquals(((AnnotatedField<?>) fieldPoint.getAnnotated()).getJavaMember(), fieldPoint.getMember());
            var method = HarnessFixtures.Unmanaged.class.getMethod("method", String.class, HarnessFixtures.Owned.class);
            Object[] parameters = enricher.resolve(method);
            assertEquals(parameters[0], "method");
            assertTrue(parameters[1] instanceof HarnessFixtures.Owned);
            var point = HarnessFixtures.POINTS.get(HarnessFixtures.POINTS.size() - 1);
            assertEquals(((AnnotatedParameter<?>) point.getAnnotated()).getPosition(), 0);
            assertEquals(point.getMember(), method);
            MicronautTestEnricher.releaseParameters();
            assertEquals(HarnessFixtures.disposed, 1);
            var failing = HarnessFixtures.Unmanaged.class.getMethod("failing", HarnessFixtures.Owned.class, HarnessFixtures.Missing.class);
            assertThrows(IllegalStateException.class, () -> enricher.resolve(failing));
            assertEquals(HarnessFixtures.disposed, 2, "Failed parameter enrichment must release earlier dependent values");
            assertThrows(org.jboss.arquillian.container.spi.client.container.DeploymentException.class, () -> container.deploy(archive));
        } finally { container.undeploy(archive); }
        assertEquals(HarnessFixtures.disposed, 3, "Field-owned dependent must survive until test deployment ends");
        assertSame(Thread.currentThread().getContextClassLoader(), previous);
        assertSame(org.eclipse.microprofile.config.spi.ConfigProviderResolver.instance(), previousResolver);
        assertNull(CurrentDeployment.uri);
        assertNull(CurrentDeployment.previousLoader);
    }
    @Test public void cleanupContinuesAfterEndpointFailureAndNeverLeavesStaleResources() throws Exception {
        AtomicInteger closed = new AtomicInteger();
        CurrentDeployment.endpoints.add(() -> closed.incrementAndGet());
        CurrentDeployment.endpoints.add(() -> { throw new IllegalStateException("endpoint failure"); });
        assertThrows(IllegalStateException.class, MicronautDeployableContainer::cleanup);
        assertEquals(closed.get(), 1);
        assertTrue(CurrentDeployment.endpoints.isEmpty());
        MicronautDeployableContainer.cleanup();
    }
    @Test public void archiveResourcesDoNotLeakAcrossDeployments() throws Exception {
        var generated = Files.createTempDirectory("runner-resource-test");
        var first = ShrinkWrap.create(JavaArchive.class).addAsResource(new org.jboss.shrinkwrap.api.asset.StringAsset("answer=one"), "META-INF/microprofile-config.properties");
        var second = ShrinkWrap.create(JavaArchive.class);
        try (var a = new ArchiveClassLoader(first, generated.toUri().toURL(), getClass().getClassLoader());
             var b = new ArchiveClassLoader(second, generated.toUri().toURL(), getClass().getClassLoader())) {
            assertNotNull(a.getResource("META-INF/microprofile-config.properties"));
            assertNull(b.getResource("META-INF/microprofile-config.properties"));
            assertFalse(b.getResources("META-INF/services/jakarta.enterprise.inject.spi.Extension").hasMoreElements());
        } finally { Files.delete(generated); }
    }
    @Test public void generatedDefinitionsAreScopedToTheirParentLoader() throws Exception {
        var directory = Files.createTempDirectory("runner-definition-test");
        try {
            var source = Files.createDirectories(directory.resolve("source/example"));
            var host = source.resolve("Host.java");
            var definition = source.resolve("$Host$Definition.java");
            Files.writeString(host, "package example; public class Host {}");
            var hostClasses = Files.createDirectories(directory.resolve("hosts"));
            var compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
            assertEquals(compiler.run(null, null, null, "-d", hostClasses.toString(), host.toString()), 0);
            var one = Files.createDirectories(directory.resolve("one"));
            var two = Files.createDirectories(directory.resolve("two"));
            Files.writeString(definition, "package example; public class $Host$Definition { public static int value() { return 1; } }");
            assertEquals(compiler.run(null, null, null, "-d", one.toString(), definition.toString()), 0);
            Files.writeString(definition, "package example; public class $Host$Definition { public static int value() { return 2; } }");
            assertEquals(compiler.run(null, null, null, "-d", two.toString(), definition.toString()), 0);
            try (var first = new URLClassLoader(new java.net.URL[]{hostClasses.toUri().toURL()}, getClass().getClassLoader());
                 var second = new URLClassLoader(new java.net.URL[]{hostClasses.toUri().toURL()}, getClass().getClassLoader())) {
                GeneratedClasses.install(one, java.util.Set.of("example.Host"), first);
                GeneratedClasses.install(two, java.util.Set.of("example.Host"), second);
                assertEquals(Class.forName("example.$Host$Definition", false, first).getMethod("value").invoke(null), 1);
                assertEquals(Class.forName("example.$Host$Definition", false, second).getMethod("value").invoke(null), 2);
                assertThrows(IllegalStateException.class, () -> GeneratedClasses.install(two, java.util.Set.of("example.Host"), first));
            }
        } finally {
            try (var paths = Files.walk(directory)) {
                for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    @Test public void serviceIsolationPreservesIntegrationProviders() throws Exception {
        var directory = Files.createTempDirectory("runner-service-test");
        String service = "META-INF/services/example.Provider";
        var integration = directory.resolve("micronaut-microprofile-tck-context-lite-1.0.jar");
        var upstream = directory.resolve("microprofile-example-tck-1.0.jar");
        try {
            for (var file : java.util.List.of(integration, upstream)) {
                try (var jar = new java.util.jar.JarOutputStream(Files.newOutputStream(file))) {
                    jar.putNextEntry(new java.util.jar.JarEntry(service));
                    jar.write((file.equals(integration) ? "integration.Provider" : "upstream.ScenarioProvider").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    jar.closeEntry();
                }
            }
            try (var parent = new URLClassLoader(new java.net.URL[]{upstream.toUri().toURL(), integration.toUri().toURL()}, getClass().getClassLoader());
                 var archive = new ArchiveClassLoader(ShrinkWrap.create(JavaArchive.class), directory.toUri().toURL(), parent)) {
                var providers = java.util.Collections.list(archive.getResources(service));
                assertEquals(providers.size(), 1);
                try (var input = providers.get(0).openStream()) {
                    assertEquals(new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8), "integration.Provider");
                }
            }
        } finally { Files.deleteIfExists(integration); Files.deleteIfExists(upstream); Files.delete(directory); }
    }

}
