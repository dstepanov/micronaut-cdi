package io.micronaut.cdi.test;

import io.micronaut.cdi.internal.runtime.CdiBeanContainer;
import io.micronaut.context.ApplicationContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.inject.Named;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A normal scoped class bean whose type a named dependent producer also produces: both are beans of the type with
 * the qualifier {@code @Default}, and the client proxy of the class bean still reaches the instance of the class bean.
 *
 * <p>Micronaut Core takes the scope of the produced class off where the producer declares one, and with it the
 * interceptor binding of the {@code ScopedProxy} a mapped scope brought, so the dependent producer gets no client
 * proxy of its own and is no second proxy target of the type.</p>
 */
class ScopedClassWithNamedProducerTest {

    @Test
    void theClientProxyOfTheClassBeanReachesItsInstance() {
        try (ApplicationContext context = ApplicationContext.run()) {
            CdiBeanContainer container = context.getBean(CdiBeanContainer.class);
            Bean<?> classBean = container.getBeans(Conn.class, Any.Literal.INSTANCE).stream()
                .filter(bean -> bean.getName() == null)
                .findFirst().orElseThrow();
            Conn reference = (Conn) container.getReference(classBean, Conn.class,
                container.createCreationalContext(classBean));
            assertEquals("class", reference.origin());
            Bean<?> produced = container.getBeans("scopedClassSecond").iterator().next();
            Conn second = (Conn) container.getReference(produced, Conn.class,
                container.createCreationalContext(produced));
            assertEquals("producer", second.origin());
        }
    }

    /**
     * The normal scoped class bean.
     */
    @ApplicationScoped
    public static class Conn {
        private final String origin;

        public Conn() {
            this("class");
        }

        Conn(String origin) {
            this.origin = origin;
        }

        public String origin() {
            return origin;
        }
    }

    /**
     * Produces another bean of the type, named and dependent, so with the qualifier {@code @Default} as well.
     */
    @ApplicationScoped
    public static class ConnFactory {
        @Produces
        @Dependent
        @Named("scopedClassSecond")
        Conn second() {
            return new Conn("producer");
        }
    }
}
