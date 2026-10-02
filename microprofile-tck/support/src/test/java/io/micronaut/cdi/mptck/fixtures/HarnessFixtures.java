/* Licensed under the Apache License, Version 2.0. */
package io.micronaut.cdi.mptck.fixtures;

import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.spi.InjectionPoint;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Provider;
import java.util.ArrayList;
import java.util.List;

public final class HarnessFixtures {
    public static final List<InjectionPoint> POINTS = new ArrayList<>();
    public static int disposed;
    public static int discoveries;
    public static class ArchiveBuild implements jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension {
        @jakarta.enterprise.inject.build.compatible.spi.Discovery
        public void discovery(jakarta.enterprise.inject.build.compatible.spi.ScannedClasses classes) { discoveries++; }
    }
    @Dependent public static class Factory {
        @Produces @Named("member") public String member(InjectionPoint point) { POINTS.add(point); return point.getMember().getName(); }
        @Produces public String competing() { return "wrong-default-producer"; }
        @Produces @Named("owned") public Owned owned() { return new Owned(); }
        public void dispose(@Disposes @Named("owned") Owned value) { disposed++; }
    }
    public static class Owned { }
    public static class UnmanagedBase {
        public int privateInitializerCalls;
        public int overriddenInitializerCalls;
        @Inject private void hidden(@Named("member") String value) { privateInitializerCalls++; }
        @Inject public void overridden(@Named("member") String value) { overriddenInitializerCalls++; }
    }
    public static class Unmanaged extends UnmanagedBase {
        private void hidden(String value) { throw new AssertionError("A private method does not override the inherited initializer"); }
        @Override public void overridden(String value) { throw new AssertionError("An unannotated override is not an initializer"); }
        @Inject @Named("member") public String field;
        @Inject @Named("member") public Provider<String> deferred;
        @Inject @Named("owned") public Owned owned;
        public String initialized;
        @Inject public void initialize(@Named("member") String value) { initialized = value; }
        public void method(@Named("member") String value, @Named("owned") Owned owned) { }
        public void failing(@Named("owned") Owned owned, Missing missing) { }
    }
    public static class Missing { }
    @jakarta.enterprise.context.RequestScoped public static class RequestBean {
        private static final java.util.concurrent.atomic.AtomicInteger CREATED = new java.util.concurrent.atomic.AtomicInteger();
        public static final java.util.concurrent.atomic.AtomicInteger DESTROYED = new java.util.concurrent.atomic.AtomicInteger();
        private final int id = CREATED.incrementAndGet();
        public int id() { return id; }
        @jakarta.annotation.PreDestroy public void close() { DESTROYED.incrementAndGet(); }
    }
}
