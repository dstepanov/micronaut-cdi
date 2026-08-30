package io.micronaut.cdi.tck;

import io.micronaut.context.ApplicationContext;
import org.jboss.cdi.tck.tests.inheritance.generics.Foo;
import org.junit.jupiter.api.Test;

class GenericsProbeTest {

    @Test
    void probe() {
        try (ApplicationContext context = ApplicationContext.run()) {
            System.out.println("DBG defs=" + context.getBeanDefinitions(Foo.class).size());
            try {
                context.getBean(io.micronaut.cdi.context.RequestScope.class).activate();
                Foo foo = context.getBean(Foo.class);
                System.out.println("DBG baz=" + foo.getBaz() + " t1=" + foo.getT1());
                jakarta.enterprise.inject.spi.BeanManager manager =
                    context.getBean(jakarta.enterprise.inject.spi.BeanManager.class);
                jakarta.enterprise.inject.spi.Bean<?> bean = manager.getBeans(Foo.class).iterator().next();
                for (jakarta.enterprise.inject.spi.InjectionPoint ip : bean.getInjectionPoints()) {
                    System.out.println("DBG IP " + ip + " type=" + ip.getType());
                }
            } catch (Throwable t) {
                Throwable cause = t;
                while (cause.getCause() != null) {
                    cause = cause.getCause();
                }
                System.out.println("DBG FAIL " + t.getClass().getSimpleName() + ": " + cause.getMessage());
            }
        }
    }
}
