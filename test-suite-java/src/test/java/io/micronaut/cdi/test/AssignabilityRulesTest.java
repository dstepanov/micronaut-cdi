
package io.micronaut.cdi.test;

import io.micronaut.cdi.runtime.CdiParameterizedType;
import io.micronaut.context.ApplicationContext;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.enterprise.util.TypeLiteral;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.Serializable;
import java.io.Writer;
import java.lang.reflect.Type;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The type rules of typesafe resolution (section 2.4.2.1) and of observer resolution (section 2.8.3), held to a
 * table of type pairs: raw types, parameterized types, arrays of both, wildcards with upper and lower bounds, and
 * type variables bounded by other variables, by several types at once, or by parameterized types. The table is
 * the one the reference implementation holds its own rules to, written again here against this implementation,
 * asked through the bean manager the way a program asks.
 *
 * <p>The variables of a pair are the type parameters of the test method, which a type literal written inside the
 * method captures. The pairs are read as the specification reads them: a required type first, then the bean type
 * that is or is not assignable to it; an observed type first, then the event type.</p>
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

    private static ApplicationContext context;
    private static BeanManager manager;

    @BeforeAll
    static void start() {
        context = ApplicationContext.run();
        manager = context.getBean(BeanManager.class);
    }

    @AfterAll
    static void stop() {
        context.close();
    }

    // -- the same type, and types that only look alike

    @Test
    <E> void aTypeMatchesItself() {
        for (Type type : List.of(Foo.class, Foo[].class, int[][].class,
            literal(new TypeLiteral<Foo<Integer>>() { }), literal(new TypeLiteral<Foo<Integer>[]>() { }),
            literal(new TypeLiteral<Foo<E>>() { }), literal(new TypeLiteral<Foo<E>[]>() { }),
            literal(new TypeLiteral<List<Integer[][]>>() { }))) {
            assertTrue(bean(type, type), type + " matches itself");
        }
        // an event is asked only of the types that can be an event type: a type variable anywhere in one leaves
        // the event without a type to be observed as, which is what anEventTypeMayNotContainATypeVariable holds
        for (Type type : List.of(Foo.class, Foo[].class, int[][].class,
            literal(new TypeLiteral<Foo<Integer>>() { }), literal(new TypeLiteral<Foo<Integer>[]>() { }),
            literal(new TypeLiteral<List<Integer[][]>>() { }))) {
            assertTrue(event(type, type), type + " is observed as itself");
        }
    }

    @Test
    void differentParameterizationsDoNotMatch() {
        Type integerFoo = literal(new TypeLiteral<Foo<Integer>>() { });
        Type stringFoo = literal(new TypeLiteral<Foo<String>>() { });
        assertFalse(bean(integerFoo, stringFoo), "Foo<Integer> is not matched by Foo<String>");
        assertFalse(event(integerFoo, stringFoo), "nor observed as one");
        Type integerFoos = literal(new TypeLiteral<Foo<Integer>[]>() { });
        Type stringFoos = literal(new TypeLiteral<Foo<String>[]>() { });
        assertFalse(bean(integerFoos, stringFoos), "Foo<Integer>[] is not matched by Foo<String>[]");
        assertFalse(event(integerFoos, stringFoos));
        assertFalse(bean(integerFoos, integerFoo), "an array is not matched by its component");
        assertFalse(event(integerFoos, integerFoo));
        assertFalse(bean(literal(new TypeLiteral<List<Integer[][]>>() { }),
            literal(new TypeLiteral<List<Number[][]>>() { })), "an argument that is an array is as invariant as any");
    }

    // -- raw and parameterized

    @Test
    <E, F extends Number> void aRawRequiredTypeIsMatchedByABeanTypeWhoseParametersSayNothing() {
        assertTrue(bean(Foo.class, literal(new TypeLiteral<Foo<Object>>() { })), "Foo is matched by Foo<Object>");
        assertTrue(bean(Foo.class, literal(new TypeLiteral<Foo<E>>() { })), "and by Foo<E>, E unbounded");
        assertFalse(bean(Foo.class, literal(new TypeLiteral<Foo<F>>() { })),
            "but not by Foo<F extends Number>, whose parameter says something");
        assertTrue(event(Foo.class, literal(new TypeLiteral<Foo<Object>>() { })), "a raw observed type observes every parameterization");
    }

    @Test
    <E> void aBeanTypeWithAnUnboundedVariableMatchesAnActualRequiredType() {
        assertTrue(bean(literal(new TypeLiteral<Foo<String>>() { }), literal(new TypeLiteral<Foo<E>>() { })),
            "Foo<String> is matched by Foo<E>: String is within E's bound");
        assertTrue(event(literal(new TypeLiteral<Foo<E>>() { }), literal(new TypeLiteral<Foo<String>>() { })),
            "and an observer of Foo<E> hears a Foo<String>");
        assertTrue(event(literal(new TypeLiteral<Foo<E>[]>() { }), literal(new TypeLiteral<Foo<String>[]>() { })));
    }

    // -- wildcards

    @Test
    void aWildcardIsMatchedByWhatFitsItsBoundsAndNeverTheOtherWayRound() {
        Type anyFoo = literal(new TypeLiteral<Foo<?>>() { });
        Type stringFoo = literal(new TypeLiteral<Foo<String>>() { });
        assertTrue(bean(anyFoo, stringFoo), "Foo<?> is matched by Foo<String>");
        assertTrue(event(anyFoo, stringFoo));
        Type anyFoos = literal(new TypeLiteral<Foo<?>[]>() { });
        Type stringFoos = literal(new TypeLiteral<Foo<String>[]>() { });
        assertTrue(bean(anyFoos, stringFoos), "Foo<?>[] is matched by Foo<String>[]");
        assertTrue(event(anyFoos, stringFoos));
        assertFalse(bean(stringFoos, anyFoos), "Foo<String>[] is not matched by Foo<?>[]: a wildcard is no bean type");
        Type listOfStringsFoo = literal(new TypeLiteral<Foo<List<String>>>() { });
        assertTrue(event(literal(new TypeLiteral<Foo<? extends List>>() { }), listOfStringsFoo),
            "Foo<? extends List> observes Foo<List<String>>");
        assertTrue(event(literal(new TypeLiteral<Foo<? extends List<String>>>() { }), listOfStringsFoo));
        assertTrue(event(anyFoo, listOfStringsFoo));
    }

    @Test
    <T1 extends Number, T2 extends T1> void aWildcardBoundedByAVariableIsBoundedAllTheWayUp() {
        Type atMostT2 = literal(new TypeLiteral<List<? extends T2>>() { });
        Type atLeastT2 = literal(new TypeLiteral<List<? super T2>>() { });
        Type numbers = literal(new TypeLiteral<List<Number>>() { });
        Type integers = literal(new TypeLiteral<List<Integer>>() { });
        Type objects = literal(new TypeLiteral<List<Object>>() { });
        assertTrue(bean(atMostT2, numbers), "List<Number> is assignable to List<? extends T2 extends T1 extends Number>");
        assertTrue(bean(atMostT2, integers), "and so is List<Integer>");
        assertFalse(bean(atMostT2, objects), "but not List<Object>");
        assertTrue(bean(atLeastT2, numbers), "List<Number> is assignable to List<? super T2 extends T1 extends Number>");
        assertTrue(bean(atLeastT2, objects), "and so is List<Object>");
        assertFalse(bean(atLeastT2, integers), "but not List<Integer>, which T2 need not be assignable to");
        assertTrue(event(atMostT2, integers));
        assertFalse(event(atMostT2, objects));
        assertTrue(event(atLeastT2, objects));
        assertFalse(event(atLeastT2, integers));
    }

    // -- type variables

    @Test
    <T1 extends Number, T2 extends T1, R1 extends Runnable, R2 extends R1> void aVariableBoundedByAVariableIsBoundedAllTheWayUp() {
        Type listOfT2 = literal(new TypeLiteral<List<T2>>() { });
        assertTrue(bean(literal(new TypeLiteral<List<Number>>() { }), listOfT2),
            "List<T2 extends T1 extends Number> is assignable to List<Number>");
        assertFalse(bean(literal(new TypeLiteral<List<Runnable>>() { }), listOfT2),
            "List<T2 extends T1 extends Number> is not assignable to List<Runnable>");
        assertTrue(bean(literal(new TypeLiteral<List<T1>>() { }), listOfT2),
            "List<T2 extends T1 extends Number> is assignable to List<T1 extends Number>");
        assertTrue(bean(listOfT2, literal(new TypeLiteral<List<T1>>() { })),
            "and List<T1 extends Number> to List<T2 extends T1 extends Number>");
        assertTrue(event(literal(new TypeLiteral<T2>() { }), Number.class), "T2 extends T1 extends Number observes a Number");
        assertFalse(event(literal(new TypeLiteral<R2>() { }), Number.class), "R2 extends R1 extends Runnable does not");
    }

    @Test
    <T1 extends Exception, T2 extends T1, T3 extends Exception, T4 extends T3, T5 extends Throwable> void variablesAreComparedByTheirUppermostBounds() {
        Type listOfT2 = literal(new TypeLiteral<List<T2>>() { });
        Type listOfT4 = literal(new TypeLiteral<List<T4>>() { });
        Type listOfT5 = literal(new TypeLiteral<List<T5>>() { });
        assertTrue(bean(listOfT4, listOfT2), "List<T2 extends T1 extends Exception> is assignable to List<T4 extends T3 extends Exception>");
        assertTrue(bean(listOfT4, listOfT5), "List<T5 extends Throwable> is assignable to List<T4 extends T3 extends Exception>");
        assertFalse(bean(listOfT5, listOfT4), "List<T4 extends T3 extends Exception> is not assignable to List<T5 extends Throwable>");
    }

    @Test
    <T1 extends List<?> & Appendable, T2 extends Writer & Serializable & Collection<?>, T3 extends T2,
        T4 extends Appendable & Iterable<?>, T5 extends T4> void everyBoundOfAVariableCounts() {
        Type ofT1 = literal(new TypeLiteral<List<T1>>() { });
        Type ofT2 = literal(new TypeLiteral<List<T2>>() { });
        Type ofT3 = literal(new TypeLiteral<List<T3>>() { });
        Type ofT4 = literal(new TypeLiteral<List<T4>>() { });
        Type ofT5 = literal(new TypeLiteral<List<T5>>() { });
        assertTrue(bean(ofT1, ofT4), "List<T4 extends Appendable & Iterable<?>> is assignable to List<T1 extends List<?> & Appendable>");
        assertTrue(bean(ofT2, ofT4), "and to List<T2 extends Writer & Serializable & Collection<?>>");
        assertTrue(bean(ofT3, ofT5), "and List<T5 extends T4> to List<T3 extends T2>, through the variables");
        assertFalse(bean(ofT4, ofT1), "List<T1 extends List<?> & Appendable> is not assignable to List<T4 extends Appendable & Iterable<?>>");
        assertFalse(bean(ofT2, ofT1), "nor to List<T2 extends Writer & Serializable & Collection<?>>");
        assertFalse(bean(ofT4, ofT2), "and List<T2> is not assignable to List<T4>");
        Type atMostT1 = literal(new TypeLiteral<List<? extends T1>>() { });
        Type atMostT2 = literal(new TypeLiteral<List<? extends T2>>() { });
        Type atMostT3 = literal(new TypeLiteral<List<? extends T3>>() { });
        Type atMostT4 = literal(new TypeLiteral<List<? extends T4>>() { });
        assertTrue(bean(atMostT1, ofT4), "and the same with a wildcard bounded by the variable");
        assertTrue(bean(atMostT2, ofT4));
        assertTrue(bean(atMostT3, ofT5));
        // a variable matches a wildcard whose bound its own is assignable to or from: T1 and T2 are neither,
        // while T4's bounds are each assignable from one of T1's, which is what makes the pairs above match
        assertFalse(bean(atMostT1, ofT2), "List<T2> is not assignable to List<? extends T1>: neither variable's bounds fit the other's");
        assertFalse(bean(atMostT2, ofT1));
        assertTrue(bean(atMostT4, ofT1), "List<T1 extends List<?> & Appendable> is assignable to List<? extends T4 extends Appendable & Iterable<?>>");
    }

    @Test
    <T1, T2 extends T1, T3 extends Collection<T1>, T4 extends Collection<T2>, T5 extends Collection<Number>,
        T6 extends Collection<Integer>, T7 extends Collection<?>> void aParameterizedBoundIsComparedAsTheLanguageAssignsIt() {
        Type ofT3 = literal(new TypeLiteral<List<T3>>() { });
        Type ofT4 = literal(new TypeLiteral<List<T4>>() { });
        Type ofT5 = literal(new TypeLiteral<List<T5>>() { });
        Type ofT6 = literal(new TypeLiteral<List<T6>>() { });
        Type ofT7 = literal(new TypeLiteral<List<T7>>() { });
        assertTrue(bean(ofT5, ofT5), "List<T5 extends Collection<Number>> is assignable to itself");
        assertFalse(bean(ofT6, ofT5), "but not to List<T6 extends Collection<Integer>>");
        assertFalse(bean(ofT5, ofT6), "nor the other way round: a parameterized bound admits only its own parameterization");
        assertFalse(bean(ofT7, ofT5), "and not to List<T7 extends Collection<?>>: Collection<?> is not a Collection<Number>");
        assertTrue(bean(ofT3, ofT3), "List<T3 extends Collection<T1>> is assignable to itself");
        assertFalse(bean(ofT4, ofT3), "but not to List<T4 extends Collection<T2 extends T1>>: the arguments are different variables");
        assertFalse(bean(ofT3, ofT4));
        assertFalse(bean(ofT7, ofT3));
    }

    @Test
    <U extends Bar & Baz, V extends SuperBaz> void aLowerBoundThatIsAVariableIsTheVariable() {
        assertTrue(bean(literal(new TypeLiteral<Box<? super U>>() { }), literal(new TypeLiteral<Box<V>>() { })),
            "Box<V extends SuperBaz> is assignable to Box<? super U extends Bar & Baz>: U is a SuperBaz through Baz");
    }

    @Test
    void anObjectArgumentIsObjectAlone() {
        Type boxOfObject = literal(new TypeLiteral<Box<Object>>() { });
        Type boxOfString = literal(new TypeLiteral<Box<String>>() { });
        assertFalse(bean(boxOfObject, boxOfString), "Box<Object> is not matched by Box<String>");
        assertTrue(bean(boxOfObject, boxOfObject), "Box<Object> is matched by itself");
        assertTrue(bean(Object.class, boxOfString), "the required type Object is matched by everything");
        assertTrue(bean(CdiParameterizedType.of(Box.class, new Type[]{Object.class}), boxOfObject),
            "a parameterized type made here equals one the language made");
    }

    // -- arrays

    @Test
    <T, S extends Integer> void anArrayIsMatchedByItsComponentAndObservedCovariantly() {
        assertTrue(bean(int[][].class, int[][].class));
        assertTrue(bean(Integer[][].class, Integer[][].class));
        assertFalse(bean(int[].class, Integer[].class), "int[] is not matched by Integer[]: no boxing inside an array");
        assertFalse(bean(Integer[].class, int[].class));
        assertFalse(event(int[].class, Integer[].class), "nor is one observed as the other");
        assertTrue(event(Number[][].class, Integer[][].class), "Number[][] observes Integer[][]: arrays are covariant");
        assertFalse(bean(Number[][].class, Integer[][].class), "but a bean type Integer[][] does not match a required Number[][]");
        assertTrue(event(List[].class, literal(new TypeLiteral<List<?>[]>() { })), "a raw array observes the arrays of every parameterization");
        assertTrue(event(literal(new TypeLiteral<T[]>() { }), Integer[][].class), "T[] observes Integer[][]: an Integer[] is a T");
        assertTrue(event(literal(new TypeLiteral<T[][]>() { }), Integer[][].class));
        assertFalse(event(literal(new TypeLiteral<S[]>() { }), Integer[][].class), "S[] where S extends Integer does not: an Integer[] is no Integer");
        assertTrue(event(literal(new TypeLiteral<S[][]>() { }), Integer[][].class));
        assertFalse(event(literal(new TypeLiteral<S[][]>() { }), Number[][].class));
    }

    // -- what the container's own API refuses of an event

    @Test
    <F extends Number> void anEventTypeMayNotContainATypeVariable() {
        assertThrows(IllegalArgumentException.class,
            () -> event(Foo.class, literal(new TypeLiteral<Foo<F>>() { })),
            "an event whose type contains a type variable has no type to be observed as (section 2.8.1)");
        assertTrue(event(literal(new TypeLiteral<Foo<Number>>() { }), literal(new TypeLiteral<Foo<Number>>() { })));
        assertFalse(event(literal(new TypeLiteral<Foo<Number>>() { }), literal(new TypeLiteral<Foo<Integer>>() { })),
            "Foo<Number> does not observe Foo<Integer>: an argument is invariant");
    }

    private static Type literal(TypeLiteral<?> literal) {
        return literal.getType();
    }

    private static boolean bean(Type required, Type beanType) {
        return manager.isMatchingBean(Set.of(beanType), Set.of(), required, Set.of());
    }

    private static boolean event(Type observed, Type eventType) {
        return manager.isMatchingEvent(eventType, Set.of(), observed, Set.of());
    }
}
