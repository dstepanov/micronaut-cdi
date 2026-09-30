package io.micronaut.cdi.test;

import io.micronaut.cdi.MicronautInstance;
import io.micronaut.context.ApplicationContext;
import io.micronaut.core.type.Argument;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Default;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.InjectionPoint;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * A dependent bean obtained by iterating a lookup is the bean {@code get()} obtains: it is given the
 * injection point of the lookup as its metadata (section 2.4.5.7), and is destroyed the same way.
 */
class InstanceIterationTest {

    static final List<String> DESTROYED = new ArrayList<>();

    @Dependent
    static class IteratedLabel {
        @Inject
        InjectionPoint at;

        @PreDestroy
        void destroyed() {
            DESTROYED.add("label");
        }
    }

    @ApplicationScoped
    static class IteratedLabels {
        @Inject
        MicronautInstance<IteratedLabel> labels;

        MicronautInstance<IteratedLabel> labels() {
            return labels;
        }
    }

    @Test
    void everyWayOfObtainingADependentBeanGivesItTheLookupsInjectionPoint() {
        try (ApplicationContext context = ApplicationContext.run()) {
            MicronautInstance<IteratedLabel> labels = context.getBean(IteratedLabels.class).labels();
            InjectionPoint expected = labels.get().at;
            assertNotNull(expected, "through get()");
            assertEquals(IteratedLabel.class, expected.getType());
            assertSame(expected, labels.iterator().next().at, "through iterator()");
            assertSame(expected, labels.stream().findFirst().orElseThrow().at, "through stream()");
            assertSame(expected, labels.getHandle().get().at, "through getHandle()");
            assertSame(expected, labels.handles().iterator().next().get().at, "through handles()");
            Instance<IteratedLabel> selected = labels.select(Default.Literal.INSTANCE);
            assertSame(expected, selected.iterator().next().at, "through select(...).iterator()");
            assertSame(expected, labels.select(Argument.of(IteratedLabel.class)).iterator().next().at,
                "through select(Argument).iterator()");
        }
    }

    @Test
    void aDependentBeanObtainedByIteratingIsDestroyedAsOneObtainedByGetIs() {
        DESTROYED.clear();
        try (ApplicationContext context = ApplicationContext.run()) {
            MicronautInstance<IteratedLabel> labels = context.getBean(IteratedLabels.class).labels();
            IteratedLabel iterated = labels.iterator().next();
            labels.destroy(iterated);
            assertEquals(List.of("label"), DESTROYED, "destroyed through the lookup that created it");
            labels.iterator().next();
            labels.get();
            assertEquals(List.of("label"), DESTROYED, "and otherwise kept for as long as the lookup is");
        }
        assertEquals(List.of("label", "label", "label"), DESTROYED,
            "what the lookup created is destroyed with the bean the lookup was injected into");
    }

    private static void assertSame(InjectionPoint expected, InjectionPoint actual, String how) {
        assertNotNull(actual, "the injection point of a dependent bean obtained " + how);
        assertEquals(expected.getType(), actual.getType(), how);
        assertEquals(expected.getBean(), actual.getBean(), how);
        assertEquals(expected.getQualifiers(), actual.getQualifiers(), how);
    }
}
