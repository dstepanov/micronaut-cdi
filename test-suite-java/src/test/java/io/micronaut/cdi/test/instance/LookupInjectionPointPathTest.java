package io.micronaut.cdi.test.instance;

import io.micronaut.context.ApplicationContext;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.InjectionPoint;
import jakarta.enterprise.util.AnnotationLiteral;
import jakarta.inject.Inject;
import jakarta.inject.Qualifier;
import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A dependent instance an injected {@code Instance} creates is created on the resolution path of the injection
 * point the lookup was injected into: Micronaut's own {@link io.micronaut.inject.InjectionPoint} and the
 * specification's {@link InjectionPoint} both read that point off the path, whichever thread the lookup is made on.
 */
class LookupInjectionPointPathTest {

    @Qualifier
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER})
    public @interface Red {

        final class Literal extends AnnotationLiteral<Red> implements Red {
            public static final Literal INSTANCE = new Literal();
        }
    }

    @Qualifier
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER})
    public @interface Blue {

        final class Literal extends AnnotationLiteral<Blue> implements Blue {
            public static final Literal INSTANCE = new Literal();
        }
    }

    public interface Service {
        InjectionPoint injectedAt();

        io.micronaut.inject.InjectionPoint<?> coreInjectedAt();
    }

    public abstract static class AbstractService implements Service {

        @Inject
        InjectionPoint injectionPoint;

        @Inject
        io.micronaut.inject.InjectionPoint<?> coreInjectionPoint;

        @Override
        public InjectionPoint injectedAt() {
            return injectionPoint;
        }

        @Override
        public io.micronaut.inject.InjectionPoint<?> coreInjectedAt() {
            return coreInjectionPoint;
        }
    }

    @Dependent
    @Red
    public static class RedService extends AbstractService {
        public RedService() {
            // the two lookups below are made concurrently: each waits until the other is creating its bean too
            Holder.creating();
        }
    }

    @Dependent
    @Blue
    public static class BlueService extends AbstractService {
        public BlueService() {
            Holder.creating();
        }
    }

    @Dependent
    public static class Holder {

        static volatile CountDownLatch bothCreating = new CountDownLatch(0);

        @Inject
        @Any
        Instance<Service> services;

        static void creating() {
            bothCreating.countDown();
            try {
                bothCreating.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Test
    void micronautsInjectionPointIsTheOneTheLookupWasInjectedInto() {
        try (ApplicationContext context = ApplicationContext.run()) {
            Service red = context.getBean(Holder.class).services.select(Red.Literal.INSTANCE).get();

            io.micronaut.inject.InjectionPoint<?> core = red.coreInjectedAt();
            assertNotNull(core, "Micronaut's injection point of a bean an injected Instance created");
            assertEquals(Holder.class, core.getDeclaringBean().getBeanType(),
                "the point is the Instance's field in Holder: " + core);
            assertEquals(Holder.class, red.injectedAt().getBean().getBeanClass());
        }
    }

    @Test
    void concurrentLookupsThroughOneInstanceEachSeeTheirOwnPoint() throws Exception {
        try (ApplicationContext context = ApplicationContext.run()) {
            Instance<Service> services = context.getBean(Holder.class).services;
            Holder.bothCreating = new CountDownLatch(2);
            try {
                CompletableFuture<Service> red = CompletableFuture.supplyAsync(
                    () -> services.select(Red.Literal.INSTANCE).get());
                CompletableFuture<Service> blue = CompletableFuture.supplyAsync(
                    () -> services.select(Blue.Literal.INSTANCE).get());

                assertTrue(red.get(20, TimeUnit.SECONDS).injectedAt().getQualifiers().contains(Red.Literal.INSTANCE));
                assertTrue(blue.get(20, TimeUnit.SECONDS).injectedAt().getQualifiers().contains(Blue.Literal.INSTANCE));
                assertTrue(!red.get().injectedAt().getQualifiers().contains(Blue.Literal.INSTANCE));
            } finally {
                Holder.bothCreating = new CountDownLatch(0);
            }
        }
    }
}
