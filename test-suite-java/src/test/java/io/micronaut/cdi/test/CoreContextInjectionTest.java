package io.micronaut.cdi.test;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.BeanContext;
import io.micronaut.context.BeanLocator;
import io.micronaut.core.value.PropertyResolver;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Micronaut hands out the context itself - as a bean context, an application context, a bean locator, a property
 * resolver - and the injection point being injected without a bean definition for any of them. A bean of the
 * specification that injects one deploys: the deployment validation does not report it as unsatisfied.
 */
class CoreContextInjectionTest {

    @Test
    void aBeanOfTheSpecificationMayInjectTheContext() {
        try (ApplicationContext context = ApplicationContext.run()) {
            NeedsContexts bean = context.getBean(NeedsContexts.class);
            assertSame(context, bean.applicationContext());
            assertSame(context, bean.beanContext());
            assertSame(context, bean.beanLocator());
            assertSame(context, bean.propertyResolver());
            assertNotNull(context.getBean(NeedsInjectionPoint.class).injectionPoint);
            NeedsResolvers resolvers = context.getBean(NeedsResolvers.class);
            assertSame(context, resolvers.watchable);
            assertNotNull(resolvers.dependencies);
        }
    }

    @ApplicationScoped
    public static class NeedsContexts {
        @Inject
        ApplicationContext applicationContext;
        @Inject
        BeanContext beanContext;
        @Inject
        BeanLocator beanLocator;
        @Inject
        PropertyResolver propertyResolver;

        public ApplicationContext applicationContext() {
            return applicationContext;
        }

        public BeanContext beanContext() {
            return beanContext;
        }

        public BeanLocator beanLocator() {
            return beanLocator;
        }

        public PropertyResolver propertyResolver() {
            return propertyResolver;
        }
    }

    @Dependent
    public static class NeedsResolvers {
        @Inject
        io.micronaut.context.WatchableBeanContext watchable;
        @Inject
        io.micronaut.context.BeanDependencyResolver dependencies;
    }

    @Dependent
    public static class NeedsInjectionPoint {
        @Inject
        io.micronaut.inject.InjectionPoint<?> injectionPoint;
    }
}
