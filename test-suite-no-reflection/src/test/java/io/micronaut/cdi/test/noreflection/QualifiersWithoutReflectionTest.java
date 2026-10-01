package io.micronaut.cdi.test.noreflection;

import io.micronaut.cdi.MicronautBeanContainer;
import io.micronaut.cdi.MicronautEvent;
import io.micronaut.cdi.MicronautInstance;
import io.micronaut.cdi.spi.CdiReflection;
import io.micronaut.context.ApplicationContext;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.type.Argument;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.enterprise.util.AnnotationLiteral;
import jakarta.enterprise.util.Nonbinding;
import jakarta.inject.Inject;
import jakarta.inject.Qualifier;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InterceptorBinding;
import jakarta.interceptor.InvocationContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An application with qualifiers of its own - with members, with a member that takes no part in resolution, and
 * with none - qualified observers and an interceptor binding, resolved and notified with no reflection module:
 * what the container compares is the values each annotation was compiled with.
 */
class QualifiersWithoutReflectionTest {

    @Qualifier
    @Retention(RetentionPolicy.RUNTIME)
    @interface Flavour {

        String value();

        @Nonbinding String note() default "";
    }

    static final class FlavourLiteral extends AnnotationLiteral<Flavour> implements Flavour {

        private final String value;

        FlavourLiteral(String value) {
            this.value = value;
        }

        @Override
        public String value() {
            return value;
        }

        @Override
        public String note() {
            return "";
        }
    }

    @Qualifier
    @Retention(RetentionPolicy.RUNTIME)
    @interface Spicy {
    }

    @InterceptorBinding
    @Retention(RetentionPolicy.RUNTIME)
    @interface Traced {
    }

    @Traced
    @Interceptor
    @jakarta.annotation.Priority(100)
    static class Tracer {

        static final AtomicInteger TRACED = new AtomicInteger();

        @AroundInvoke
        Object trace(InvocationContext context) throws Exception {
            TRACED.incrementAndGet();
            return context.proceed();
        }
    }

    interface Dish {
        String name();
    }

    @Flavour("sweet")
    @Dependent
    static class Cake implements Dish {
        @Override
        public String name() {
            return "cake";
        }
    }

    @Flavour(value = "sour", note = "with sugar")
    @Dependent
    static class Lemonade implements Dish {
        @Override
        public String name() {
            return "lemonade";
        }
    }

    @Spicy
    @Dependent
    static class Curry implements Dish {
        @Override
        public String name() {
            return "curry";
        }
    }

    record Order(String what) {
    }

    @ApplicationScoped
    static class Kitchen {

        final List<String> heard = new ArrayList<>();

        void any(@Observes Order order) {
            heard.add("any:" + order.what());
        }

        void sweet(@Observes @Flavour(value = "sweet", note = "observed") Order order) {
            heard.add("sweet:" + order.what());
        }

        void sour(@Observes @Flavour("sour") Order order) {
            heard.add("sour:" + order.what());
        }

        void spicy(@Observes @Spicy Order order) {
            heard.add("spicy:" + order.what());
        }

        List<String> heard() {
            return List.copyOf(heard);
        }

        @Traced
        String serve() {
            return "served";
        }
    }

    @ApplicationScoped
    static class Table {

        @Inject
        @Flavour("sweet")
        Dish dessert;

        @Inject
        @Spicy
        Dish main;

        @Inject
        @Any
        Instance<Dish> everything;

        @Inject
        @Any
        MicronautInstance<Dish> dishes;

        @Inject
        MicronautEvent<Order> orders;

        @Inject
        @Flavour("sour")
        Event<Order> sourOrders;

        String dessert() {
            return dessert.name();
        }

        String main() {
            return main.name();
        }

        Instance<Dish> everything() {
            return everything;
        }

        MicronautInstance<Dish> dishes() {
            return dishes;
        }

        MicronautEvent<Order> orders() {
            return orders;
        }

        Event<Order> sourOrders() {
            return sourOrders;
        }
    }

    private ApplicationContext context;
    private Table table;
    private Kitchen kitchen;

    @BeforeEach
    void start() {
        context = ApplicationContext.run();
        table = context.getBean(Table.class);
        kitchen = context.getBean(Kitchen.class);
    }

    @AfterEach
    void stop() {
        context.close();
    }

    private static AnnotationValue<Flavour> flavour(String value, String note) {
        return AnnotationValue.builder(Flavour.class).value(value).member("note", note).build();
    }

    private static UnsupportedOperationException assertNamesTheModule(org.junit.jupiter.api.function.Executable call) {
        UnsupportedOperationException thrown = assertThrows(UnsupportedOperationException.class, call);
        assertTrue(thrown.getMessage().contains(CdiReflection.MODULE), thrown.getMessage());
        return thrown;
    }

    @Test
    void thereIsNoReflectionModule() {
        assertFalse(context.findBean(CdiReflection.class).isPresent());
    }

    @Test
    void anInjectionPointIsResolvedByItsQualifier() {
        assertEquals("cake", table.dessert());
        assertEquals("curry", table.main());
    }

    @Test
    void aLookupIsSelectedByAnnotationValue() {
        // the member that takes no part in resolution is given another value than the bean was written with
        assertEquals("cake", table.dishes().select(flavour("sweet", "whatever")).get().name());
        assertEquals("lemonade", table.dishes().select(flavour("sour", "")).get().name());
        assertTrue(table.dishes().select(flavour("bitter", "")).isUnsatisfied());
        assertEquals("curry", table.dishes().select(AnnotationValue.builder(Spicy.class).build()).get().name());
        assertEquals("cake", table.dishes().select(Cake.class, flavour("sweet", "")).get().name());
        assertEquals("cake", table.dishes().select(Argument.of(Dish.class), flavour("sweet", "")).get().name());
    }

    @Test
    void everyLookupTheContainerHandsOutSelectsByAnnotationValue() {
        assertInstanceOf(MicronautInstance.class, table.everything());
        MicronautInstance<Object> current = assertInstanceOf(MicronautInstance.class, CDI.current());
        assertEquals("cake", current.select(Dish.class, flavour("sweet", "x")).get().name());
        MicronautBeanContainer container =
            assertInstanceOf(MicronautBeanContainer.class, CDI.current().getBeanContainer());
        assertEquals("lemonade",
            container.createInstance().select(Dish.class, flavour("sour", "y")).get().name());
    }

    @Test
    void aMarkerQualifierGivenAsALiteralNeedsNoReflection() {
        assertEquals("curry", table.everything().select(new AnnotationLiteral<Spicy>() { }).get().name());
        assertEquals(3, count(table.everything()));
    }

    private static int count(Iterable<?> all) {
        int count = 0;
        for (Object ignored : all) {
            count++;
        }
        return count;
    }

    @Test
    void aLiteralWithMembersNeedsTheModuleAndNamesTheAlternative() {
        UnsupportedOperationException thrown =
            assertNamesTheModule(() -> table.everything().select(new FlavourLiteral("sweet")));
        assertTrue(thrown.getMessage().contains("MicronautInstance"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("AnnotationValue"), thrown.getMessage());
        assertNamesTheModule(() -> table.orders().select(new FlavourLiteral("sweet")));
        assertNamesTheModule(() -> CDI.current().getBeanContainer().getBeans(Dish.class, new FlavourLiteral("sweet")));
    }

    @Test
    void theContainerLooksBeansUpByArgumentAndAnnotationValue() {
        MicronautBeanContainer container = (MicronautBeanContainer) CDI.current().getBeanContainer();
        Set<Bean<?>> sweet = container.getBeans(Argument.of(Dish.class), flavour("sweet", "z"));
        assertEquals(1, sweet.size());
        assertEquals(Cake.class, sweet.iterator().next().getBeanClass());
        assertEquals(3, container.getBeans(Argument.of(Dish.class),
            AnnotationValue.builder(Any.class).build()).size());
        assertEquals(2, container.resolveObserverMethods(new Order("probe"), flavour("sour", "")).size());
    }

    @Test
    void aSelectionByAnnotationValueIsHeldToTheRulesOfTheSpecification() {
        assertThrows(IllegalArgumentException.class,
            () -> table.dishes().select(AnnotationValue.builder(Traced.class).build()));
        assertThrows(IllegalArgumentException.class,
            () -> table.dishes().select(flavour("sweet", ""), flavour("sour", "")));
        assertThrows(IllegalArgumentException.class,
            () -> table.orders().select(flavour("sweet", ""), flavour("sweet", "")));
    }

    @Test
    void anEventIsObservedByItsQualifier() {
        table.orders().fire(new Order("plain"));
        assertEquals(List.of("any:plain"), kitchen.heard());
    }

    @Test
    void anEventSelectedByAnnotationValueNotifiesTheObserversOfTheQualifier() {
        table.orders().select(flavour("sweet", "fired")).fire(new Order("tart"));
        assertEquals(Set.of("any:tart", "sweet:tart"), Set.copyOf(kitchen.heard()));
    }

    @Test
    void anInjectedQualifiedEventNotifiesTheObserversOfItsQualifier() {
        table.sourOrders().fire(new Order("pickle"));
        assertEquals(Set.of("any:pickle", "sour:pickle"), Set.copyOf(kitchen.heard()));
    }

    @Test
    void anEventSelectedByAMarkerLiteralNeedsNoReflection() {
        table.orders().select(new AnnotationLiteral<Spicy>() { }).fire(new Order("chili"));
        assertEquals(Set.of("any:chili", "spicy:chili"), Set.copyOf(kitchen.heard()));
    }

    @Test
    void anEventSelectedByArgumentIsFiredAsThatType() {
        table.orders().select(Argument.of(Order.class), AnnotationValue.builder(Spicy.class).build())
            .fire(new Order("pepper"));
        assertEquals(Set.of("any:pepper", "spicy:pepper"), Set.copyOf(kitchen.heard()));
    }

    @Test
    void anInterceptorIsBoundByItsBinding() {
        int before = Tracer.TRACED.get();
        assertEquals("served", kitchen.serve());
        assertEquals(before + 1, Tracer.TRACED.get());
    }

    @Test
    void theQualifiersOfABeanAsAnnotationInstancesNeedTheModule() {
        MicronautBeanContainer container = (MicronautBeanContainer) CDI.current().getBeanContainer();
        Bean<?> cake = container.getBeans(Argument.of(Dish.class), flavour("sweet", "")).iterator().next();
        assertNamesTheModule(cake::getQualifiers);
    }
}
