package io.micronaut.cdi.test;

import io.micronaut.context.ApplicationContext;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.enterprise.util.TypeLiteral;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@code BeanManager.getReference} hands out a reference only as one of the bean's types (CDI 4.1 section
 * 11.3.2): a wildcard parameterization is never a bean type (section 2.2.1), and a raw class is one only where
 * the bean's parameterization of it asks nothing of its arguments, as the rules of section 5.2.4 assign it.
 */
class ReferenceTypeTest {

    @Test
    void aTypeThatIsNotABeanTypeIsRefused() {
        try (ApplicationContext context = ApplicationContext.run()) {
            BeanManager manager = context.getBean(BeanManager.class);
            Bean<?> bean = manager.resolve(manager.getBeans(StringShelf.class));
            Type wildcard = new TypeLiteral<Shelf<? extends CharSequence>>() { }.getType();
            assertThrows(IllegalArgumentException.class,
                () -> manager.getReference(bean, wildcard, manager.createCreationalContext(bean)));
            assertThrows(IllegalArgumentException.class,
                () -> manager.getReference(bean, Shelf.class, manager.createCreationalContext(bean)));

            Type exact = new TypeLiteral<Shelf<String>>() { }.getType();
            assertInstanceOf(StringShelf.class, manager.getReference(bean, exact,
                manager.createCreationalContext(bean)));
            assertInstanceOf(StringShelf.class, manager.getReference(bean, StringShelf.class,
                manager.createCreationalContext(bean)));
        }
    }

    /**
     * A generic type.
     *
     * @param <T> The type
     */
    public interface Shelf<T> {
    }

    /**
     * A bean whose type parameterizes it.
     */
    @Dependent
    public static class StringShelf implements Shelf<String> {
    }
}
