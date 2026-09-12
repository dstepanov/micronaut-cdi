
package io.micronaut.cdi.test;

import io.micronaut.cdi.runtime.CdiParameterizedType;
import io.micronaut.context.ApplicationContext;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.enterprise.util.TypeLiteral;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The type rules of section 2.4.2 on the pairs the reference implementation's own tests found this implementation
 * wrong on: arrays of parameterized types, and type variables bounded by other variables, by several types at
 * once, or by parameterized types. Weld's tests run in test-suite-weld; these keep each finding beside the kit's
 * own, asked through the bean manager the way a program asks.
 *
 * <p>The variables of a pair are the type parameters of the test method, which a type literal written inside the
 * method captures.</p>
 */
class AssignabilityRulesTest {

    interface Foo<T> {
    }

    interface SuperBaz {
    }

    interface Baz extends SuperBaz {
    }

    interface Bar {
    }

    static class Box<T> {
    }

    @Test
    void anArrayOfAParameterizedTypeMatchesByItsComponent() {
        try (ApplicationContext context = ApplicationContext.run()) {
            BeanManager manager = context.getBean(BeanManager.class);
            Type stringFoos = new TypeLiteral<Foo<String>[]>() { }.getType();
            Type anyFoos = new TypeLiteral<Foo<?>[]>() { }.getType();
            Type integerFoos = new TypeLiteral<Foo<Integer>[]>() { }.getType();
            assertTrue(matches(manager, anyFoos, stringFoos), "Foo<?>[] is matched by Foo<String>[]");
            assertFalse(matches(manager, integerFoos, stringFoos), "Foo<Integer>[] is not matched by Foo<String>[]");
            assertTrue(event(manager, integerFoos, integerFoos), "an array event type is observed as itself");
            assertTrue(event(manager, List[].class, new TypeLiteral<List<?>[]>() { }.getType()),
                "a raw array observes the arrays of every parameterization");
        }
    }

    @Test
    <T1 extends Number, T2 extends T1> void aVariableBoundedByAVariableIsBoundedAllTheWayUp() {
        try (ApplicationContext context = ApplicationContext.run()) {
            BeanManager manager = context.getBean(BeanManager.class);
            Type listOfT2 = new TypeLiteral<List<T2>>() { }.getType();
            assertTrue(matches(manager, new TypeLiteral<List<Number>>() { }.getType(), listOfT2),
                "List<T2 extends T1 extends Number> is assignable to List<Number>");
            assertFalse(matches(manager, new TypeLiteral<List<Runnable>>() { }.getType(), listOfT2),
                "List<T2 extends T1 extends Number> is not assignable to List<Runnable>");
            Type listOfAtMostT2 = new TypeLiteral<List<? extends T2>>() { }.getType();
            assertTrue(matches(manager, listOfAtMostT2, new TypeLiteral<List<Integer>>() { }.getType()),
                "List<Integer> is assignable to List<? extends T2 extends T1 extends Number>");
            assertFalse(matches(manager, listOfAtMostT2, new TypeLiteral<List<Object>>() { }.getType()),
                "List<Object> is not assignable to List<? extends T2 extends T1 extends Number>");
            assertFalse(event(manager, listOfAtMostT2, new TypeLiteral<List<Object>>() { }.getType()),
                "nor is a List<Object> event observed as one");
        }
    }

    @Test
    <T4 extends Appendable & Iterable<?>, T5 extends List<?> & Appendable> void everyBoundOfAVariableCounts() {
        try (ApplicationContext context = ApplicationContext.run()) {
            BeanManager manager = context.getBean(BeanManager.class);
            Type listOfT4 = new TypeLiteral<List<T4>>() { }.getType();
            Type listOfT5 = new TypeLiteral<List<T5>>() { }.getType();
            assertTrue(matches(manager, listOfT5, listOfT4),
                "List<T4 extends Appendable & Iterable<?>> is assignable to List<T5 extends List<?> & Appendable>");
            assertFalse(matches(manager, listOfT4, listOfT5),
                "List<T5 extends List<?> & Appendable> is not assignable to List<T4 extends Appendable & Iterable<?>>");
        }
    }

    @Test
    <T6 extends Collection<Number>, T7 extends Collection<Integer>> void aParameterizedBoundIsComparedAsTheLanguageAssignsIt() {
        try (ApplicationContext context = ApplicationContext.run()) {
            BeanManager manager = context.getBean(BeanManager.class);
            Type listOfT6 = new TypeLiteral<List<T6>>() { }.getType();
            Type listOfT7 = new TypeLiteral<List<T7>>() { }.getType();
            assertTrue(matches(manager, listOfT6, listOfT6), "a variable is assignable to itself");
            assertFalse(matches(manager, listOfT7, listOfT6),
                "List<T6 extends Collection<Number>> is not assignable to List<T7 extends Collection<Integer>>");
            assertFalse(matches(manager, listOfT6, listOfT7),
                "nor the other way round: a parameterized bound admits only its own parameterization");
        }
    }

    @Test
    <U extends Bar & Baz, V extends SuperBaz> void aLowerBoundThatIsAVariableIsTheVariable() {
        try (ApplicationContext context = ApplicationContext.run()) {
            BeanManager manager = context.getBean(BeanManager.class);
            assertTrue(matches(manager, new TypeLiteral<Box<? super U>>() { }.getType(),
                    new TypeLiteral<Box<V>>() { }.getType()),
                "Box<V extends SuperBaz> is assignable to Box<? super U extends Bar & Baz>: U is a SuperBaz through Baz");
        }
    }

    @Test
    void anObjectArgumentIsObjectAlone() {
        try (ApplicationContext context = ApplicationContext.run()) {
            BeanManager manager = context.getBean(BeanManager.class);
            Type boxOfObject = new TypeLiteral<Box<Object>>() { }.getType();
            Type boxOfString = new TypeLiteral<Box<String>>() { }.getType();
            assertFalse(matches(manager, boxOfObject, boxOfString), "Box<Object> is not matched by Box<String>");
            assertTrue(matches(manager, boxOfObject, boxOfObject), "Box<Object> is matched by itself");
            assertTrue(matches(manager, Object.class, boxOfString), "the required type Object is matched by everything");
            assertTrue(matches(manager, CdiParameterizedType.of(Box.class, new Type[]{Object.class}), boxOfObject),
                "a parameterized type made here equals one the language made");
        }
    }

    private static boolean matches(BeanManager manager, Type required, Type bean) {
        return manager.isMatchingBean(Set.of(bean), Set.of(), required, Set.of());
    }

    private static boolean event(BeanManager manager, Type observed, Type event) {
        return manager.isMatchingEvent(event, Set.of(), observed, Set.of());
    }
}
