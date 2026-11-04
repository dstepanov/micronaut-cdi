package io.micronaut.cdi.test.event;

import io.micronaut.context.ApplicationContext;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.spi.CDI;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Section 10.4: an observer method is a method of a managed bean class, and a class that declares neither a
 * constructor taking no parameters nor one annotated {@code Inject} is not one (section 3.1.1). Since Micronaut Core
 * 5.3 (#14220) javac hands the visitors a class whose only annotations are on the parameters of its methods, as KSP
 * and Groovy hand them every class, so such a class is read and declares no observer.
 */
class ObserverOnNonManagedClassTest {

    public record Knock(String who) {
    }

    public static class NoBeanConstructor {
        public NoBeanConstructor(String name) {
        }

        public void observe(@Observes @Any Knock event) {
        }
    }

    @Test
    void aClassWithoutABeanConstructorDeclaresNoObserver() {
        try (ApplicationContext context = ApplicationContext.run()) {
            assertTrue(CDI.current().getBeanManager().resolveObserverMethods(new Knock("x")).stream()
                .noneMatch(observer -> observer.getBeanClass() == NoBeanConstructor.class));
            assertTrue(context.findBeanDefinition(NoBeanConstructor.class).isEmpty());
        }
    }
}
