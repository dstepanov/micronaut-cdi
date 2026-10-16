package io.micronaut.cdi.test;

import io.micronaut.cdi.internal.runtime.CdiParameterizedType;
import io.micronaut.cdi.internal.type.SpecificationTypes;
import io.micronaut.cdi.reflection.ReflectiveCdi;
import io.micronaut.core.type.Argument;
import jakarta.enterprise.util.TypeLiteral;
import org.junit.jupiter.api.Test;

import java.io.Serializable;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The type closures and event types the reflection module reads from the generic signatures of a class, through
 * Micronaut's reading of them: a wildcard stays a wildcard, a variable a variable, what a subtype says about its
 * parameters is carried into its supertypes, and {@code Object} is left out.
 */
class ReflectiveTypeClosureTest {

    static class Book {
    }

    @SuppressWarnings("serial")
    static class Books extends ArrayList<Book> implements Comparable<Books> {
        @Override
        public int compareTo(Books o) {
            return 0;
        }
    }

    static class Holder<T> implements Supplier<T> {
        @Override
        public T get() {
            return null;
        }
    }

    static class Foo<F> {
    }

    static class Bar<B> extends Foo<B> {
    }

    private final ReflectiveCdi reflection = new ReflectiveCdi();

    @Test
    void theArgumentsOfASubtypeAreCarriedIntoItsSupertypes() {
        Set<String> closure = described(reflection.typeClosureOf(Books.class, true));
        assertTrue(closure.contains(Books.class.getName()));
        assertTrue(closure.contains("java.util.ArrayList<" + Book.class.getName() + ">"), closure::toString);
        assertTrue(closure.contains("java.util.List<" + Book.class.getName() + ">"), closure::toString);
        assertTrue(closure.contains("java.lang.Iterable<" + Book.class.getName() + ">"), closure::toString);
        assertTrue(closure.contains("java.lang.Comparable<" + Books.class.getName() + ">"), closure::toString);
        assertTrue(closure.contains(Serializable.class.getName()), closure::toString);
        assertFalse(closure.contains(Object.class.getName()), "Object is left out");
    }

    @Test
    void aWildcardStaysAWildcard() {
        Type holder = new TypeLiteral<Holder<? extends Number>>() { }.getType();
        Set<String> closure = described(reflection.typeClosureOf(holder, true));
        assertTrue(closure.contains("java.util.function.Supplier<? extends java.lang.Number>"), closure::toString);
        Type lower = new TypeLiteral<Holder<? super Integer>>() { }.getType();
        assertTrue(described(reflection.typeClosureOf(lower, true))
            .contains("java.util.function.Supplier<? super java.lang.Integer>"));
    }

    @Test
    void aVariableStaysAVariable() {
        Set<String> closure = described(reflection.typeClosureOf(reflection.declaredTypeOf(Holder.class), true));
        assertTrue(closure.contains("java.util.function.Supplier<T>"), closure::toString);
    }

    @Test
    void aVariableTheContainerRenderedIsReadThroughItsBounds() {
        // the container renders a variable from its argument with bounds but no annotated bounds, and hands it
        // back to the reflection module as it is
        Type bounded = SpecificationTypes.typeOf(Argument.of(Holder.class,
            Argument.ofTypeVariable(Number.class, null, "U", null, Argument.ZERO_ARGUMENTS,
                new Argument<?>[] {Argument.of(Number.class)})));
        TypeVariable<?> rendered = (TypeVariable<?>) ((ParameterizedType) bounded).getActualTypeArguments()[0];
        assertEquals(0, rendered.getAnnotatedBounds().length);
        Set<String> closure = described(reflection.typeClosureOf(bounded, true));
        assertTrue(closure.contains("java.util.function.Supplier<U>"), closure::toString);

        Type unbounded = SpecificationTypes.typeOf(Argument.of(Holder.class, Argument.ofTypeVariable(Object.class, null, "V")));
        Set<String> unboundedClosure = described(reflection.typeClosureOf(unbounded, true));
        assertTrue(unboundedClosure.contains("java.util.function.Supplier<V>"), unboundedClosure::toString);
    }

    @Test
    void anArrayHasNoClosureBeyondItselfWhereItStops() {
        assertEquals(List.of(String[].class), reflection.typeClosureOf(String[].class, true));
        Set<String> beyond = described(reflection.typeClosureOf(String[].class, false));
        assertTrue(beyond.contains(Serializable.class.getName()) && beyond.contains(Cloneable.class.getName()),
            beyond::toString);
        assertFalse(beyond.contains(Object.class.getName()), "Object is left out");
    }

    @Test
    void aVariableOfTheEventClassIsResolvedThroughItsSupertype() {
        Type declared = CdiParameterizedType.of(Foo.class, new Type[] {Integer.class});
        assertEquals(Bar.class.getName() + "<java.lang.Integer>",
            describe(reflection.eventTypeOf(Bar.class, declared)));
    }

    private static Set<String> described(List<Type> types) {
        return types.stream().map(ReflectiveTypeClosureTest::describe).collect(Collectors.toSet());
    }

    private static String describe(Type type) {
        if (type instanceof Class<?> raw) {
            return raw.getName();
        }
        if (type instanceof ParameterizedType parameterized) {
            StringBuilder described = new StringBuilder(describe(parameterized.getRawType())).append('<');
            Type[] arguments = parameterized.getActualTypeArguments();
            for (int i = 0; i < arguments.length; i++) {
                described.append(i == 0 ? "" : ", ").append(describe(arguments[i]));
            }
            return described.append('>').toString();
        }
        if (type instanceof WildcardType wildcard) {
            if (wildcard.getLowerBounds().length > 0) {
                return "? super " + describe(wildcard.getLowerBounds()[0]);
            }
            Type[] uppers = wildcard.getUpperBounds();
            return uppers.length == 0 || uppers[0] == Object.class ? "?" : "? extends " + describe(uppers[0]);
        }
        if (type instanceof TypeVariable<?> variable) {
            return variable.getName();
        }
        return type.getTypeName();
    }
}
