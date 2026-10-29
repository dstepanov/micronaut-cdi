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

import io.micronaut.context.ApplicationContext;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.InterceptorBinding;
import jakarta.interceptor.InvocationContext;
import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * What a disposer is invoked on and in which order its arguments are resolved: a public disposer of a bean in a
 * normal scope is invoked through the client proxy, and so through the interceptors bound to it, while one that is
 * not public is invoked on the contextual instance behind the proxy; and the parameters of a disposer are resolved
 * before the instance it is invoked on.
 */
class DisposerInvocationTargetTest {

    static final List<String> SEEN = new CopyOnWriteArrayList<>();

    @InterceptorBinding
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD})
    @interface Watched {
    }

    @Watched
    @jakarta.interceptor.Interceptor
    @Priority(100)
    public static class WatchInterceptor {
        @AroundInvoke
        Object watch(InvocationContext context) throws Exception {
            SEEN.add("intercepted " + context.getMethod().getName());
            return context.proceed();
        }
    }

    record PublicProduct(String id) {
    }

    record HiddenProduct(String id) {
    }

    record OrderedProduct(String id) {
    }

    /**
     * Produces in a normal scope, so that a client proxy stands in front of it, with one public and one
     * package-private disposer, both bound to the interceptor.
     */
    @ApplicationScoped
    public static class Products {

        @Produces
        @Dependent
        PublicProduct publicProduct() {
            return new PublicProduct("public");
        }

        @Produces
        @Dependent
        HiddenProduct hiddenProduct() {
            return new HiddenProduct("hidden");
        }

        @Watched
        public void releasePublic(@Disposes PublicProduct product) {
            SEEN.add("disposed " + product.id());
        }

        @Watched
        void releaseHidden(@Disposes HiddenProduct product) {
            SEEN.add("disposed " + product.id());
        }
    }

    /**
     * A disposer parameter, which reports being created.
     */
    @Dependent
    public static class Witness {
        public Witness() {
            SEEN.add("parameter created");
        }
    }

    /**
     * A dependent declaring bean, a new instance of which the disposer is invoked on, which reports being created.
     */
    @Dependent
    public static class OrderedProducts {
        public OrderedProducts() {
            SEEN.add("target created");
        }

        @Produces
        @Dependent
        OrderedProduct orderedProduct() {
            return new OrderedProduct("ordered");
        }

        void release(@Disposes OrderedProduct product, Witness witness) {
            SEEN.add("disposed " + product.id());
        }
    }

    @Test
    void aPublicDisposerIsInterceptedAndOneThatIsNotPublicIsInvokedOnTheInstance() {
        try (ApplicationContext context = ApplicationContext.run()) {
            Instance<PublicProduct> publicProducts = CDI.current().select(PublicProduct.class);
            Instance<HiddenProduct> hiddenProducts = CDI.current().select(HiddenProduct.class);
            PublicProduct publicProduct = publicProducts.get();
            HiddenProduct hiddenProduct = hiddenProducts.get();
            SEEN.clear();
            publicProducts.destroy(publicProduct);
            assertEquals(List.of("intercepted releasePublic", "disposed public"), SEEN);
            SEEN.clear();
            hiddenProducts.destroy(hiddenProduct);
            assertEquals(List.of("disposed hidden"), SEEN);
        }
    }

    @Test
    void theParametersOfADisposerAreResolvedBeforeTheInstanceItIsInvokedOn() {
        try (ApplicationContext context = ApplicationContext.run()) {
            Instance<OrderedProduct> products = CDI.current().select(OrderedProduct.class);
            OrderedProduct product = products.get();
            SEEN.clear();
            products.destroy(product);
            assertEquals(List.of("parameter created", "target created", "disposed ordered"), SEEN);
        }
    }
}
