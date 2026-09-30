package org.example.cdi.se;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.se.SeContainer;
import jakarta.enterprise.inject.se.SeContainerInitializer;
import jakarta.enterprise.util.AnnotationLiteral;
import jakarta.inject.Qualifier;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * What a class selected into the synthetic archive of an SE bootstrap declares is in the archive with it: the
 * beans its producers produce, whatever their types, its disposers and its observers.
 *
 * <p>The classes are outside {@code io.micronaut}, as an application's are: what is under it is the container's
 * own and is in every archive.</p>
 */
class SeSelectedClassMembersTest {

    static final List<String> DISPOSED = new ArrayList<>();
    static final List<String> HEARD = new ArrayList<>();

    @Qualifier
    @Retention(RetentionPolicy.RUNTIME)
    @interface Made {
    }

    record Signal(String text) {
    }

    @ApplicationScoped
    static class Workshop {

        @Produces
        @Made
        Integer count = 3;

        @Produces
        @Made
        String name() {
            return "made";
        }

        void dispose(@Disposes @Made String name) {
            DISPOSED.add(name);
        }

        void hear(@Observes Signal signal) {
            HEARD.add(signal.text());
        }
    }

    @Test
    void aSelectedClassBringsWhatItsMembersDeclare() {
        verify(SeContainerInitializer.newInstance().disableDiscovery().addBeanClasses(Workshop.class));
    }

    @Test
    void aSelectedPackageBringsWhatTheMembersOfItsClassesDeclare() {
        verify(SeContainerInitializer.newInstance().disableDiscovery().addPackages(Workshop.class));
    }

    private static void verify(SeContainerInitializer initializer) {
        DISPOSED.clear();
        HEARD.clear();
        AnnotationLiteral<Made> made = new AnnotationLiteral<>() {
        };
        try (SeContainer container = initializer.initialize()) {
            Instance<String> names = container.select(String.class, made);
            String name = names.get();
            assertEquals("made", name, "the bean a producer method of the selected class produces");
            assertEquals(3, container.select(Integer.class, made).get(),
                "the bean a producer field of the selected class produces");
            names.destroy(name);
            assertEquals(List.of("made"), DISPOSED, "the disposer of the selected class");
            container.getBeanManager().getEvent().select(Signal.class).fire(new Signal("heard"));
            assertEquals(List.of("heard"), HEARD, "the observer of the selected class");
        }
    }
}
