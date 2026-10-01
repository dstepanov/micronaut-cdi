/* Licensed under the Apache License, Version 2.0. */
package io.micronaut.cdi.mptck.streams;

import org.eclipse.microprofile.reactive.streams.operators.tck.ReactiveStreamsTck;
import org.eclipse.microprofile.reactive.streams.operators.spi.ReactiveStreamsEngine;
import org.reactivestreams.tck.TestEnvironment;
import jakarta.enterprise.inject.se.SeContainer;
import io.micronaut.cdi.se.MicronautSeContainerInitializer;
import java.util.ServiceLoader;

/** The upstream factory supplies every assertion. The chosen engine is obtained from Micronaut CDI. */
public final class SmallRyeStreamsTck extends ReactiveStreamsTck<ReactiveStreamsEngine> {
    private SeContainer container;
    private ClassLoader previousLoader;
    public SmallRyeStreamsTck() { super(new TestEnvironment(1000)); }
    @Override @org.testng.annotations.Factory
    public Object[] allTests() {
        try { return super.allTests(); }
        catch (RuntimeException | Error failure) {
            try {
                java.nio.file.Path output = java.nio.file.Path.of(System.getProperty("mp.tck.evidence"));
                java.nio.file.Files.createDirectories(output);
                java.io.StringWriter trace = new java.io.StringWriter();
                failure.printStackTrace(new java.io.PrintWriter(trace));
                java.nio.file.Files.writeString(output.resolve("factory-failure.txt"), trace.toString());
            } catch (java.io.IOException writing) { failure.addSuppressed(writing); }
            throw failure;
        }
    }
    @Override protected ReactiveStreamsEngine createEngine() {
        try {
            previousLoader = Thread.currentThread().getContextClassLoader();
            ClassLoader isolated = new ClassLoader(previousLoader) {
                @Override public java.util.Enumeration<java.net.URL> getResources(String name) throws java.io.IOException {
                    if (name.equals("META-INF/services/jakarta.enterprise.inject.spi.Extension")) return java.util.Collections.emptyEnumeration();
                    return super.getResources(name);
                }
            };
            Thread.currentThread().setContextClassLoader(isolated);
            var initializer = System.getProperty("mp.tck.mode").equals("reference")
                ? (jakarta.enterprise.inject.se.SeContainerInitializer) Class.forName("org.jboss.weld.environment.se.Weld").getConstructor().newInstance()
                : new MicronautSeContainerInitializer();
            container = initializer.setClassLoader(isolated).disableDiscovery().addBeanClasses(EngineProducer.class).initialize();
            return container.select(ReactiveStreamsEngine.class).get();
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot start the reference CDI container", failure);
        } catch (RuntimeException | Error failure) {
            failure.printStackTrace();
            throw failure;
        }
    }
    @Override protected void shutdownEngine(ReactiveStreamsEngine engine) {
        try { if (container != null) container.close(); }
        finally { if (previousLoader != null) Thread.currentThread().setContextClassLoader(previousLoader); }
    }
    @jakarta.enterprise.context.Dependent
    public static class EngineProducer {
        @jakarta.enterprise.inject.Produces
        public ReactiveStreamsEngine engine() { return ServiceLoader.load(ReactiveStreamsEngine.class).findFirst().orElseThrow(); }
    }
}
