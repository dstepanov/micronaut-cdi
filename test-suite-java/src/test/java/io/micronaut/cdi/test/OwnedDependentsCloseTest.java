package io.micronaut.cdi.test;

import io.micronaut.cdi.internal.runtime.OwnedDependents;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.scope.CreatedBean;
import io.micronaut.inject.BeanDefinition;
import io.micronaut.inject.BeanIdentifier;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Closing an owner of dependent instances attempts every destruction, after one that fails with an error as after
 * one that fails with an exception, and reports the first failure.
 */
class OwnedDependentsCloseTest {

    @Test
    void everyHeldInstanceIsDestroyedAfterAnError() {
        List<String> closed = new ArrayList<>();
        try (ApplicationContext context = ApplicationContext.run()) {
            OwnedDependents dependents = OwnedDependents.of(context);
            dependents.hold(new Held("first", closed, null));
            dependents.hold(new Held("second", closed, new AssertionError("second fails")));
            AssertionError error = assertThrows(AssertionError.class, dependents::close);
            assertEquals("second fails", error.getMessage());
        }
        // the last held is destroyed first
        assertEquals(List.of("second", "first"), closed);
    }

    private record Held(String name, List<String> closed, Error failure) implements CreatedBean<Object> {

        @Override
        public BeanDefinition<Object> definition() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Object bean() {
            return name;
        }

        @Override
        public BeanIdentifier id() {
            return BeanIdentifier.of(name);
        }

        @Override
        public void close() {
            closed.add(name);
            if (failure != null) {
                throw failure;
            }
        }
    }
}
