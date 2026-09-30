package io.micronaut.cdi.test.noreflection;

import io.micronaut.cdi.runtime.CdiBeanContainer;
import io.micronaut.cdi.runtime.CdiReflection;
import io.micronaut.context.ApplicationContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Default;
import jakarta.enterprise.inject.Stereotype;
import jakarta.enterprise.inject.spi.InjectionPoint;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The parts of the specification's API that hand out reflection objects, without the module that answers them:
 * each fails with an exception that names the module, and what needs no reflection beside them still answers.
 */
class ReflectiveApiWithoutReflectionTest {

    @Dependent
    static class Probe {

        @Inject
        InjectionPoint injectionPoint;
    }

    @ApplicationScoped
    static class Holder {

        @Inject
        Probe probe;

        InjectionPoint injectionPointOfTheProbe() {
            return probe.injectionPoint;
        }
    }

    @Stereotype
    @Retention(RetentionPolicy.RUNTIME)
    @interface Plain {
    }

    private ApplicationContext context;
    private CdiBeanContainer container;

    @BeforeEach
    void start() {
        context = ApplicationContext.run();
        container = context.getBean(CdiBeanContainer.class);
    }

    @AfterEach
    void stop() {
        context.close();
    }

    private static void assertNamesTheModule(Executable call) {
        UnsupportedOperationException thrown = assertThrows(UnsupportedOperationException.class, call);
        assertTrue(thrown.getMessage().contains(CdiReflection.MODULE), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("io.micronaut.cdi:micronaut-cdi-reflection"), thrown.getMessage());
    }

    @Test
    void theMemberOfAnInjectionPointNeedsTheModule() {
        InjectionPoint injectionPoint = context.getBean(Holder.class).injectionPointOfTheProbe();
        // what was compiled for the injection point is answered as before
        assertEquals(Probe.class, injectionPoint.getType());
        assertEquals(Holder.class, injectionPoint.getBean().getBeanClass());
        assertNamesTheModule(injectionPoint::getMember);
        assertNamesTheModule(injectionPoint::getAnnotated);
        assertNamesTheModule(injectionPoint::isTransient);
    }

    @Test
    void anAnnotationTheBuildRecordedNothingOfNeedsTheModule() {
        // the annotations of the specification are known for what they are
        assertTrue(container.isScope(ApplicationScoped.class));
        assertTrue(container.isNormalScope(ApplicationScoped.class));
        assertFalse(container.isNormalScope(Dependent.class));
        assertTrue(container.isQualifier(Default.class));
        assertTrue(container.isQualifier(Named.class));
        // anything else is asked of the annotation class itself
        assertNamesTheModule(() -> container.isQualifier(Plain.class));
        assertNamesTheModule(() -> container.isScope(Plain.class));
        assertNamesTheModule(() -> container.isStereotype(Plain.class));
        assertNamesTheModule(() -> container.isInterceptorBinding(Plain.class));
    }

    @Test
    void theDefinitionOfAStereotypeNeedsTheModule() {
        assertNamesTheModule(() -> container.getStereotypeDefinition(Plain.class));
        assertNamesTheModule(() -> container.getInterceptorBindingDefinition(Plain.class));
    }
}
