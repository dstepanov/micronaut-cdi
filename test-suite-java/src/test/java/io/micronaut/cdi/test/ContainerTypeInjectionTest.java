package io.micronaut.cdi.test;

import io.micronaut.context.ApplicationContext;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.spi.InjectionPoint;
import jakarta.inject.Inject;
import jakarta.inject.Qualifier;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.lang.reflect.ParameterizedType;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static java.lang.annotation.ElementType.*;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static org.junit.jupiter.api.Assertions.*;

class ContainerTypeInjectionTest {
    enum Shape { OPTIONAL, LIST, SET, COLLECTION }

    @Qualifier
    @Retention(RUNTIME)
    @Target({FIELD, METHOD, PARAMETER})
    @interface Container { Shape value(); }

    record Value(String text) { }

    @Dependent
    static class Producers {
        static final Set<Object> CREATED = Collections.newSetFromMap(new IdentityHashMap<>());
        static final Set<Object> DISPOSED = Collections.newSetFromMap(new IdentityHashMap<>());
        static final List<InjectionPoint> POINTS = new ArrayList<>();

        private <T> T record(T result, InjectionPoint point, Class<?> rawType, Shape shape) {
            assertNotNull(point);
            assertEquals(rawType, ((ParameterizedType) point.getType()).getRawType());
            assertEquals(Value.class, ((ParameterizedType) point.getType()).getActualTypeArguments()[0]);
            assertTrue(point.getQualifiers().stream().anyMatch(q -> q instanceof Container c && c.value() == shape));
            POINTS.add(point);
            CREATED.add(result);
            return result;
        }

        @Produces @Container(Shape.OPTIONAL)
        Optional<Value> optional(InjectionPoint point) {
            return record(Optional.of(new Value("optional")), point, Optional.class, Shape.OPTIONAL);
        }
        @Produces @Container(Shape.LIST)
        List<Value> list(InjectionPoint point) {
            return record(new ArrayList<>(List.of(new Value("list"))), point, List.class, Shape.LIST);
        }
        @Produces @Container(Shape.SET)
        Set<Value> set(InjectionPoint point) {
            return record(new java.util.HashSet<>(Set.of(new Value("set"))), point, Set.class, Shape.SET);
        }
        @Produces @Container(Shape.COLLECTION)
        Collection<Value> collection(InjectionPoint point) {
            return record(new ArrayList<>(List.of(new Value("collection"))), point, Collection.class, Shape.COLLECTION);
        }
        // A matching element producer must not supply or augment the List bean.
        @Produces @Container(Shape.LIST)
        Value element() { return new Value("element"); }

        void optionalDisposed(@Disposes @Container(Shape.OPTIONAL) Optional<Value> value) { DISPOSED.add(value); }
        void listDisposed(@Disposes @Container(Shape.LIST) List<Value> value) { DISPOSED.add(value); }
        void setDisposed(@Disposes @Container(Shape.SET) Set<Value> value) { DISPOSED.add(value); }
        void collectionDisposed(@Disposes @Container(Shape.COLLECTION) Collection<Value> value) { DISPOSED.add(value); }
    }

    @Dependent
    static class Fields {
        @Inject @Container(Shape.OPTIONAL) Optional<Value> optional;
        @Inject @Container(Shape.LIST) List<Value> list;
        @Inject @Container(Shape.SET) Set<Value> set;
        @Inject @Container(Shape.COLLECTION) Collection<Value> collection;
        @Inject @Container(Shape.LIST) List<Value> $list;
    }

    @Dependent
    static class Constructor {
        final Optional<Value> optional;
        final List<Value> list;
        final Set<Value> set;
        @Inject Constructor(@Container(Shape.OPTIONAL) Optional<Value> optional,
                            @Container(Shape.LIST) List<Value> list,
                            @Container(Shape.SET) Set<Value> set) {
            this.optional = optional; this.list = list; this.set = set;
        }
    }

    @Dependent
    static class Initializer {
        Optional<Value> optional;
        List<Value> list;
        Set<Value> set;
        @Inject void initialize(@Container(Shape.OPTIONAL) Optional<Value> optional,
                                @Container(Shape.LIST) List<Value> list,
                                @Container(Shape.SET) Set<Value> set) {
            this.optional = optional; this.list = list; this.set = set;
        }
    }

    @Test
    void declaredContainerBeansAreInjectedAndDisposedWithTheirOwner() {
        Producers.CREATED.clear(); Producers.DISPOSED.clear(); Producers.POINTS.clear();
        try (ApplicationContext context = ApplicationContext.run()) {
            var registration = context.getBeanRegistration(Fields.class, null);
            Fields fields = registration.getBean();
            assertValues(fields.optional, fields.list, fields.set);
            assertEquals(List.of(new Value("collection")), fields.collection);
            assertEquals(List.of(new Value("list")), fields.$list);
            assertTrue(Producers.POINTS.stream().allMatch(point -> point.getBean().getBeanClass() == Fields.class));
            assertEquals(Set.of("optional", "list", "set", "collection", "$list"),
                Producers.POINTS.stream().map(point -> point.getMember().getName()).collect(java.util.stream.Collectors.toSet()));
            registration.close();
            assertEquals(5, Producers.DISPOSED.size());
            assertTrue(Producers.CREATED.stream().allMatch(Producers.DISPOSED::contains));

            var constructorRegistration = context.getBeanRegistration(Constructor.class, null);
            Constructor constructor = constructorRegistration.getBean();
            assertValues(constructor.optional, constructor.list, constructor.set);
            var initializerRegistration = context.getBeanRegistration(Initializer.class, null);
            Initializer initializer = initializerRegistration.getBean();
            assertValues(initializer.optional, initializer.list, initializer.set);
            assertTrue(Producers.POINTS.stream().anyMatch(point -> point.getBean().getBeanClass() == Constructor.class
                && point.getMember() instanceof java.lang.reflect.Constructor<?>));
            assertTrue(Producers.POINTS.stream().anyMatch(point -> point.getBean().getBeanClass() == Initializer.class
                && point.getMember().getName().equals("initialize")));
            constructorRegistration.close();
            initializerRegistration.close();
        }
        assertEquals(11, Producers.CREATED.size());
        assertEquals(11, Producers.DISPOSED.size());
        assertTrue(Producers.CREATED.stream().allMatch(Producers.DISPOSED::contains));
    }

    private static void assertValues(Optional<Value> optional, List<Value> list, Set<Value> set) {
        assertEquals(Optional.of(new Value("optional")), optional);
        assertEquals(List.of(new Value("list")), list);
        assertEquals(Set.of(new Value("set")), set);
    }
}
