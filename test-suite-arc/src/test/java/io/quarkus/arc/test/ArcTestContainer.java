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
package io.quarkus.arc.test;

import io.micronaut.context.ApplicationContext;
import io.micronaut.inject.BeanDefinition;
import io.micronaut.inject.ProxyBeanDefinition;
import io.quarkus.arc.Arc;
import io.quarkus.arc.ArcContainer;
import io.quarkus.arc.Vetoed;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The container one of ArC's tests runs against: an application context narrowed to the classes the test named.
 *
 * <p>Every test of ArC registers one of these and says which classes its container is to hold. In ArC that list
 * is an index, and the container is built from it as the test begins. Here every class of the suite was compiled
 * into a bean definition in one go, so the list is a filter instead: the context started for the test holds a
 * definition of the suite's own namespace only where the test named its class, and holds everything outside that
 * namespace regardless, because that is the container itself — the scopes, the bean manager, the observers.</p>
 *
 * <p>Naming the class exactly, rather than the class it is nested in, is what keeps one test's beans out of
 * another's container even though they share a compilation. Nested classes are already namespaced by their outer
 * class, so two tests cannot collide by name; what they could collide over is resolution — an alternative, a
 * priority, a {@code @Named} of the same name — and they do not, because a definition is held only for the test
 * that named it. A bean produced by a member of a class belongs to the class that declares the member, whatever
 * type it produces, so it is filtered by its declaring class rather than by its own.</p>
 *
 * <p>ArC's {@code additionalClasses} are indexed without being a bean archive, which is to say their classes are
 * there to be resolved against but are not themselves discovered. They are not added to the filter for that
 * reason.</p>
 */
public class ArcTestContainer implements BeforeEachCallback, AfterEachCallback {

    /**
     * The namespace the fetched tests live in, and so the definitions a deployment decides about.
     */
    private static final String SUITE = "io.quarkus.arc.test.";

    private final List<Class<?>> beanClasses;
    private final List<Class<?>> additionalClasses;
    private final boolean shouldFail;

    private @Nullable Throwable failure;
    private @Nullable ArcContainer container;

    /**
     * @param beanClasses The classes whose beans the container is to hold
     */
    public ArcTestContainer(Class<?>... beanClasses) {
        this.beanClasses = Arrays.asList(beanClasses);
        this.additionalClasses = Collections.emptyList();
        this.shouldFail = false;
    }

    private ArcTestContainer(Builder builder) {
        this.beanClasses = builder.beanClasses;
        this.additionalClasses = builder.additionalClasses;
        this.shouldFail = builder.shouldFail;
    }

    /**
     * @return A builder for a container that is more than a list of classes
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * The failure a deployment the test expected to be rejected was rejected with.
     *
     * @return The failure, or null where the deployment came up
     */
    public @Nullable Throwable getFailure() {
        return failure;
    }

    @Override
    public void beforeEach(ExtensionContext extensionContext) {
        failure = null;
        Set<String> deployed = new LinkedHashSet<>(beanClasses.size());
        for (Class<?> beanClass : beanClasses) {
            deployed.add(beanClass.getName());
        }
        try {
            ApplicationContext context = ApplicationContext.builder()
                .beansPredicate(definition -> holds(definition, deployed))
                .build()
                .start();
            container = new ArcContainer(context);
            Arc.install(container);
        } catch (RuntimeException | Error e) {
            if (!shouldFail) {
                throw e;
            }
            failure = e;
        }
    }

    @Override
    public void afterEach(ExtensionContext extensionContext) {
        ArcContainer running = container;
        container = null;
        Arc.install(null);
        if (running != null) {
            running.shutdown();
        }
    }

    /**
     * Whether the deployment holds a definition: everything outside the suite's namespace, and of the suite's own
     * only what the test named.
     */
    private static boolean holds(io.micronaut.inject.BeanType<?> bean, Set<String> deployed) {
        if (bean instanceof BeanDefinition<?> definition) {
            Class<?> declaring = definition.getDeclaringType().orElse(null);
            if (declaring != null && declaring.getName().startsWith(SUITE)
                && !declaring.equals(bean.getBeanType())) {
                // a produced bean belongs to the class that declares its producer, whatever type it produces:
                // a produced String carries no package of its own to be filtered by
                return deployed.contains(beanClassOf(declaring.getName())) && !isVetoed(declaring);
            }
        }
        Class<?> type = bean instanceof ProxyBeanDefinition<?> proxy ? proxy.getTargetType() : bean.getBeanType();
        String name = beanClassOf(type.getName());
        if (!name.startsWith(SUITE)) {
            return true;
        }
        return deployed.contains(name) && !isVetoed(type);
    }

    /**
     * The class a definition is the definition of.
     *
     * <p>A bean whose methods are intercepted has a second definition of its own, for the subclass the compiler
     * wrote to intercept them, and the bean type of that one is the subclass: {@code $MyBean$Definition$Intercepted}
     * beside {@code MyBean}. Both belong to the test that named {@code MyBean}, so the decoration is taken off
     * before the name is compared. A nested class's own dollars are left alone - only the generated suffix and the
     * leading dollar of a generated simple name are.</p>
     */
    private static String beanClassOf(String name) {
        int dot = name.lastIndexOf('.');
        String packagePrefix = name.substring(0, dot + 1);
        String simple = name.substring(dot + 1);
        if (simple.startsWith("$")) {
            simple = simple.substring(1);
        }
        int generated = simple.indexOf("$Definition");
        if (generated >= 0) {
            simple = simple.substring(0, generated);
        }
        return packagePrefix + simple;
    }

    /**
     * Whether ArC's own {@code @Vetoed} keeps the class out, on the class itself or on its package.
     */
    private static boolean isVetoed(Class<?> type) {
        for (Class<?> each = type; each != null; each = each.getEnclosingClass()) {
            if (each.isAnnotationPresent(Vetoed.class)) {
                return true;
            }
        }
        return type.getPackage() != null && type.getPackage().isAnnotationPresent(Vetoed.class);
    }

    /**
     * The builder a test uses where it has more to say than a list of bean classes.
     */
    public static class Builder {

        private final List<Class<?>> beanClasses = new ArrayList<>();
        private final List<Class<?>> additionalClasses = new ArrayList<>();
        private boolean shouldFail;

        /**
         * @param beanClasses The classes whose beans the container is to hold
         * @return This builder
         */
        public Builder beanClasses(Class<?>... beanClasses) {
            Collections.addAll(this.beanClasses, beanClasses);
            return this;
        }

        /**
         * @param additionalClasses Classes the deployment resolves against without discovering them as beans
         * @return This builder
         */
        public Builder additionalClasses(Class<?>... additionalClasses) {
            Collections.addAll(this.additionalClasses, additionalClasses);
            return this;
        }

        /**
         * @return This builder, set to expect the deployment to be rejected
         */
        public Builder shouldFail() {
            this.shouldFail = true;
            return this;
        }

        /**
         * @return The container
         */
        public ArcTestContainer build() {
            return new ArcTestContainer(this);
        }
    }
}
