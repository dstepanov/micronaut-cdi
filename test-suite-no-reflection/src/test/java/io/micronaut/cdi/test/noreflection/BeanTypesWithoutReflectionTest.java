package io.micronaut.cdi.test.noreflection;

import io.micronaut.cdi.MicronautBeanContainer;
import io.micronaut.cdi.MicronautInstance;
import io.micronaut.cdi.spi.CdiReflection;
import io.micronaut.context.ApplicationContext;
import io.micronaut.core.type.Argument;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bean types of a bean - its class and everything above it, with the type arguments the hierarchy gives
 * each - are what the processor recorded, so a parameterized lookup is resolved with no reflection module on
 * the classpath.
 */
class BeanTypesWithoutReflectionTest {

    interface Store<T> {
        T take();
    }

    abstract static class Shelf<K, T> implements Store<T> {
    }

    @Dependent
    static class TextShelf extends Shelf<Integer, String> {
        @Override
        public String take() {
            return "text";
        }
    }

    @Dependent
    static class NumberStore implements Store<Long> {
        @Override
        public Long take() {
            return 42L;
        }
    }

    @ApplicationScoped
    static class Producers {

        @Produces
        @Dependent
        List<Integer> numbers() {
            return List.of(1, 2, 3);
        }
    }

    @ApplicationScoped
    static class Reader {

        @Inject
        Store<String> texts;

        @Inject
        Store<Long> numbers;

        @Inject
        Instance<Store<String>> lookup;

        @Inject
        List<Integer> produced;

        String text() {
            return texts.take();
        }

        Long number() {
            return numbers.take();
        }

        String lookedUp() {
            return lookup.get().take();
        }

        List<Integer> produced() {
            return produced;
        }
    }

    private ApplicationContext context;

    @BeforeEach
    void start() {
        context = ApplicationContext.run();
    }

    @AfterEach
    void stop() {
        context.close();
    }

    @Test
    void aParameterizedInjectionPointIsResolvedByItsTypeArguments() {
        assertFalse(context.findBean(CdiReflection.class).isPresent());
        Reader reader = context.getBean(Reader.class);
        assertEquals("text", reader.text());
        assertEquals(42L, reader.number());
        assertEquals("text", reader.lookedUp());
        assertEquals(List.of(1, 2, 3), reader.produced());
    }

    @Test
    void aParameterizedLookupIsResolvedByArgument() {
        MicronautBeanContainer container = (MicronautBeanContainer) CDI.current().getBeanContainer();
        MicronautInstance<Object> lookup = container.createInstance();
        assertEquals("text", lookup.select(Argument.of(Store.class, String.class)).get().take());
        assertEquals(42L, lookup.select(Argument.of(Store.class, Long.class)).get().take());
        assertTrue(lookup.select(Argument.of(Store.class, Double.class)).isUnsatisfied());
        assertEquals(1, container.getBeans(Argument.of(Store.class, String.class)).size());
    }

    @Test
    void theBeanTypesAreTheClosureTheHierarchyGives() {
        MicronautBeanContainer container = (MicronautBeanContainer) CDI.current().getBeanContainer();
        Bean<?> shelf = container.getBeans(Argument.of(TextShelf.class)).iterator().next();
        Set<Type> types = shelf.getTypes();
        assertEquals(4, types.size(), types.toString());
        assertTrue(types.contains(TextShelf.class));
        assertTrue(types.contains(Object.class));
        boolean store = false;
        boolean shelfOfIntegerAndString = false;
        for (Type type : types) {
            if (type instanceof ParameterizedType parameterized) {
                if (parameterized.getRawType() == Store.class) {
                    store = parameterized.getActualTypeArguments()[0] == String.class;
                }
                if (parameterized.getRawType() == Shelf.class) {
                    shelfOfIntegerAndString = parameterized.getActualTypeArguments()[0] == Integer.class
                        && parameterized.getActualTypeArguments()[1] == String.class;
                }
            }
        }
        assertTrue(store, types.toString());
        assertTrue(shelfOfIntegerAndString, types.toString());
    }
}
