package io.micronaut.cdi.test.customscope;

import io.micronaut.context.ApplicationContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

/**
 * Each container has the context of an extension's scope to itself: what one container's context holds is not in
 * another's.
 */
class MapContextIsolationTest {

    @Test
    void twoContainersHoldTheirOwnInstances() {
        try (ApplicationContext first = ApplicationContext.run();
             ApplicationContext second = ApplicationContext.run()) {
            first.getBean(ExtensionScopeBridgeTest.Scoped.class).id();
            assertNotSame(MapContexts.of(first), MapContexts.of(second));
            assertEquals(1, MapContexts.of(first).size());
            assertEquals(0, MapContexts.of(second).size(), "the second container created nothing");
        }
    }
}
