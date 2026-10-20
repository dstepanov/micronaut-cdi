package io.micronaut.cdi.internal.runtime;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.RuntimeBeanDefinition;
import io.micronaut.core.annotation.AnnotationValue;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What portable extensions register as the container starts - the qualifiers they add to a bean class and the
 * annotations they make qualifiers - is kept whatever the number of additions and however many threads add at once:
 * each addition is seen by every later read, none is lost to another made at the same time, and the qualifiers of a
 * class keep the order they were added in.
 */
class ExtensionQualifierRegistrationTest {

    private static final int THREADS = 8;
    private static final int PER_THREAD = 200;

    static final class Widget {
    }

    static final class Gadget {
    }

    @Test
    void theQualifiersAddedToAClassAreAppliedInTheOrderTheyWereAdded() {
        QualifierOverlay overlay = new QualifierOverlay();
        CdiQualifier first = qualifier("org.example.First");
        CdiQualifier second = qualifier("org.example.Second");
        CdiQualifier named = CdiQualifier.named("widget");
        overlay.add(Widget.class.getName(), first);
        overlay.add(Widget.class.getName(), named);
        overlay.add(Widget.class.getName(), second);
        // the same qualifier added twice is one qualifier of the bean
        overlay.add(Widget.class.getName(), first);

        List<CdiQualifier> applied = overlay.apply(definitionOf(Widget.class),
            List.of(CdiQualifier.ANY, CdiQualifier.DEFAULT));
        // a qualifier other than Any and Named takes Default away
        assertEquals(List.of(CdiQualifier.ANY, first, named, second), applied);
        List<CdiQualifier> untouched = List.of(CdiQualifier.ANY, CdiQualifier.DEFAULT);
        assertSame(untouched, overlay.apply(definitionOf(Gadget.class), untouched),
            "a class nothing was added to keeps what it was compiled with");
    }

    @Test
    void noAdditionToTheBeanClassesIsLostToAnotherMadeAtTheSameTime() throws Exception {
        QualifierOverlay overlay = new QualifierOverlay();
        String[] classes = {Widget.class.getName(), Gadget.class.getName()};
        concurrently(thread -> {
            for (int i = 0; i < PER_THREAD; i++) {
                // both classes from every thread, so that writers of one class and of the other race
                overlay.add(classes[i % 2], CdiQualifier.named(thread + "-" + i));
            }
        });
        List<CdiQualifier> declared = List.of(CdiQualifier.ANY);
        int widgets = overlay.apply(definitionOf(Widget.class), declared).size() - 1;
        int gadgets = overlay.apply(definitionOf(Gadget.class), declared).size() - 1;
        assertEquals(THREADS * PER_THREAD / 2, widgets);
        assertEquals(THREADS * PER_THREAD / 2, gadgets);
        // each thread's additions to a class are in the order it made them
        List<CdiQualifier> applied = overlay.apply(definitionOf(Widget.class), declared);
        for (int thread = 0; thread < THREADS; thread++) {
            int previous = -1;
            for (int i = 0; i < PER_THREAD; i += 2) {
                int at = indexOf(applied, CdiQualifier.named(thread + "-" + i));
                assertTrue(at > previous, "the addition " + thread + "-" + i + " follows the one before it");
                previous = at;
            }
        }
    }

    @Test
    void noAnnotationMadeAQualifierIsLostToAnotherMadeAtTheSameTime() throws Exception {
        try (ApplicationContext context = ApplicationContext.builder().build()) {
            CompiledAnnotationTypes types = new CompiledAnnotationTypes(context);
            concurrently(thread -> {
                for (int i = 0; i < PER_THREAD; i++) {
                    types.addQualifier("org.example.Qualifier" + thread + "x" + i);
                    // registered again by another extension
                    types.addQualifier("org.example.Shared" + i);
                }
            });
            for (int thread = 0; thread < THREADS; thread++) {
                for (int i = 0; i < PER_THREAD; i++) {
                    assertEquals(Boolean.TRUE, types.isQualifier("org.example.Qualifier" + thread + "x" + i));
                    assertEquals(Boolean.TRUE, types.isQualifier("org.example.Shared" + i));
                }
            }
        }
    }

    private static int indexOf(List<CdiQualifier> qualifiers, CdiQualifier qualifier) {
        for (int i = 0; i < qualifiers.size(); i++) {
            if (qualifier.isAmong(List.of(qualifiers.get(i)))) {
                return i;
            }
        }
        return -1;
    }

    private interface Writer {
        void write(int thread);
    }

    private static void concurrently(Writer writer) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> writes = new ArrayList<>();
            for (int thread = 0; thread < THREADS; thread++) {
                int id = thread;
                writes.add(pool.submit(() -> {
                    start.await();
                    writer.write(id);
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> write : writes) {
                write.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private static CdiQualifier qualifier(String name) {
        return CdiQualifier.ofValue(null, AnnotationValue.builder(name).build());
    }

    private static <T> RuntimeBeanDefinition<T> definitionOf(Class<T> type) {
        return RuntimeBeanDefinition.of(type, () -> null);
    }
}
