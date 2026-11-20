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
package io.micronaut.cdi.test;

import io.micronaut.cdi.internal.runtime.CdiBeanContainer;
import io.micronaut.context.ApplicationContext;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.InterceptionType;
import jakarta.enterprise.util.AnnotationLiteral;
import jakarta.inject.Inject;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.InterceptorBinding;
import jakarta.interceptor.InvocationContext;
import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The built-in beans of bean metadata (CDI 5.0, section 5.5.8 of CDI Lite): a bean is handed its own {@code Bean}
 * and an interceptor its own {@code Interceptor}, each the metadata the bean container reports of it. The
 * {@code @Intercepted Bean} is not implemented (CONFORMANCE.md, Not yet implemented).
 */
class BeanMetadataInjectionTest {

    @Test
    void aDependentBeanIsHandedItsOwnMetadataAtEachKindOfInjectionPoint() {
        try (ApplicationContext context = ApplicationContext.run()) {
            CdiBeanContainer container = context.getBean(CdiBeanContainer.class);
            DependentSelf self = container.createInstance().select(DependentSelf.class).get();
            Bean<?> expected = container.resolve(container.getBeans(DependentSelf.class));
            assertMetadata(expected, self.field, DependentSelf.class);
            assertMetadata(expected, self.constructor, DependentSelf.class);
            assertMetadata(expected, self.initializer, DependentSelf.class);
        }
    }

    @Test
    void anApplicationScopedBeanIsHandedItsOwnMetadataAtEachKindOfInjectionPoint() {
        try (ApplicationContext context = ApplicationContext.run()) {
            CdiBeanContainer container = context.getBean(CdiBeanContainer.class);
            ApplicationScopedSelf self = container.createInstance().select(ApplicationScopedSelf.class).get();
            Bean<?> expected = container.resolve(container.getBeans(ApplicationScopedSelf.class));
            assertMetadata(expected, self.field(), ApplicationScopedSelf.class);
            assertMetadata(expected, self.constructor(), ApplicationScopedSelf.class);
            assertMetadata(expected, self.initializer(), ApplicationScopedSelf.class);
            assertEquals(ApplicationScoped.class, self.field().getScope());
        }
    }

    @Test
    void aProducerMethodIsHandedTheMetadataOfTheBeanItProduces() {
        try (ApplicationContext context = ApplicationContext.run()) {
            CdiBeanContainer container = context.getBean(CdiBeanContainer.class);
            Produced produced = container.createInstance().select(Produced.class).get();
            Bean<?> expected = container.resolve(container.getBeans(Produced.class));
            assertNotNull(produced.bean());
            assertEquals(expected, produced.bean());
            assertEquals(expected.getTypes(), produced.bean().getTypes());
            assertEquals(expected.getQualifiers(), produced.bean().getQualifiers());
            assertEquals(ProducedFactory.class, produced.bean().getBeanClass(),
                "the bean class of a producer method is the class declaring it");
        }
    }

    @Test
    void anInterceptorIsHandedItsOwnMetadata() {
        Recording.LAST.set(null);
        try (ApplicationContext context = ApplicationContext.run()) {
            CdiBeanContainer container = context.getBean(CdiBeanContainer.class);
            assertEquals("called", container.createInstance().select(InterceptedTarget.class).get().call());
            Recording recording = Recording.LAST.get();
            assertNotNull(recording, "the interceptor intercepted the call");

            List<jakarta.enterprise.inject.spi.Interceptor<?>> resolved = container.resolveInterceptors(
                InterceptionType.AROUND_INVOKE, new Recorded.Literal());
            assertEquals(1, resolved.size());
            assertEquals(resolved.get(0), recording.self);
            assertEquals(Recording.class, recording.self.getBeanClass());
            assertEquals(resolved.get(0).getInterceptorBindings(), recording.self.getInterceptorBindings());
            assertEquals(recording.self, recording.bean, "the Bean of an interceptor is its Interceptor");
        }
    }

    private static void assertMetadata(Bean<?> expected, Bean<?> injected, Class<?> beanClass) {
        assertNotNull(injected);
        assertEquals(expected, injected);
        assertEquals(beanClass, injected.getBeanClass());
        assertEquals(expected.getTypes(), injected.getTypes());
        assertEquals(expected.getQualifiers(), injected.getQualifiers());
        assertSame(expected.getScope(), injected.getScope());
    }

    /**
     * Told its own metadata at a field, a constructor and an initializer.
     */
    @Dependent
    public static class DependentSelf {

        @Inject
        Bean<DependentSelf> field;

        final Bean<DependentSelf> constructor;

        Bean<DependentSelf> initializer;

        @Inject
        public DependentSelf(Bean<DependentSelf> constructor) {
            this.constructor = constructor;
        }

        @Inject
        void initialize(Bean<DependentSelf> bean) {
            this.initializer = bean;
        }
    }

    /**
     * Told its own metadata at a field, a constructor and an initializer, behind a client proxy.
     */
    @ApplicationScoped
    public static class ApplicationScopedSelf {

        @Inject
        Bean<ApplicationScopedSelf> field;

        private final Bean<ApplicationScopedSelf> constructor;

        private Bean<ApplicationScopedSelf> initializer;

        protected ApplicationScopedSelf() {
            this.constructor = null;
        }

        @Inject
        public ApplicationScopedSelf(Bean<ApplicationScopedSelf> constructor) {
            this.constructor = constructor;
        }

        @Inject
        void initialize(Bean<ApplicationScopedSelf> bean) {
            this.initializer = bean;
        }

        public Bean<ApplicationScopedSelf> field() {
            return field;
        }

        public Bean<ApplicationScopedSelf> constructor() {
            return constructor;
        }

        public Bean<ApplicationScopedSelf> initializer() {
            return initializer;
        }
    }

    /**
     * What the producer below produces, carrying the metadata it was handed.
     *
     * @param bean The metadata of the produced bean
     */
    public record Produced(Bean<Produced> bean) {
    }

    /**
     * Declares a producer method told the metadata of the bean it produces.
     */
    @Dependent
    public static class ProducedFactory {

        @Produces
        Produced produce(Bean<Produced> bean) {
            return new Produced(bean);
        }
    }

    /**
     * Records the metadata it was handed.
     */
    @jakarta.interceptor.Interceptor
    @Recorded
    @Priority(jakarta.interceptor.Interceptor.Priority.APPLICATION)
    public static class Recording {

        static final AtomicReference<Recording> LAST = new AtomicReference<>();

        @Inject
        jakarta.enterprise.inject.spi.Interceptor<Recording> self;

        @Inject
        Bean<Recording> bean;

        @AroundInvoke
        Object record(InvocationContext context) throws Exception {
            LAST.set(this);
            return context.proceed();
        }
    }

    /**
     * Binds the interceptor that records.
     */
    @InterceptorBinding
    @Target({ElementType.TYPE, ElementType.METHOD})
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Recorded {

        /**
         * The literal.
         */
        final class Literal extends AnnotationLiteral<Recorded> implements Recorded {
        }
    }

    /**
     * The intercepted bean.
     */
    @Dependent
    @Recorded
    public static class InterceptedTarget {

        public String call() {
            return "called";
        }
    }
}
