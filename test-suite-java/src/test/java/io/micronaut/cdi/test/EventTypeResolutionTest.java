package io.micronaut.cdi.test;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EventTypeResolutionTest {

    @SuppressWarnings("unused")
    static class Duck<T> { }

    @SuppressWarnings("unused")
    static class Foo<F> { }

    static class Bar<B> extends Foo<B> { }

    @Test
    void aDeclaredParameterResolvesTheVariable() {
        Type declared = io.micronaut.cdi.runtime.CdiParameterizedType.of(Duck.class, new Type[]{String.class});
        Type resolved = io.micronaut.cdi.runtime.CdiTypes.eventTypeOf(Duck.class, declared);
        assertEquals("io.micronaut.cdi.test.EventTypeResolutionTest$Duck<java.lang.String>",
            resolved.getTypeName());
    }

    @Test
    void aSupertypeParameterResolvesTheSubtypeVariable() {
        Type declared = io.micronaut.cdi.runtime.CdiParameterizedType.of(Foo.class, new Type[]{Integer.class});
        Type resolved = io.micronaut.cdi.runtime.CdiTypes.eventTypeOf(Bar.class, declared);
        assertEquals("io.micronaut.cdi.test.EventTypeResolutionTest$Bar<java.lang.Integer>",
            resolved.getTypeName());
    }
}
