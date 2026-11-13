package io.micronaut.cdi.test.groovy

import io.micronaut.cdi.internal.metadata.CdiApplicationScope
import io.micronaut.cdi.internal.metadata.CdiProducer
import io.micronaut.cdi.internal.metadata.CdiScope
import io.micronaut.context.ApplicationContext
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.context.Dependent
import jakarta.enterprise.context.NormalScope
import jakarta.enterprise.inject.Produces
import jakarta.enterprise.inject.Stereotype
import jakarta.inject.Singleton
import org.junit.jupiter.api.Test

import java.lang.annotation.ElementType
import java.lang.annotation.Inherited
import java.lang.annotation.Retention
import java.lang.annotation.RetentionPolicy
import java.lang.annotation.Target

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertSame
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * A bean of a normal scope of the application's own compiled by the Groovy compiler is held first by the scope's own
 * annotation - declared, inherited or declared by a producer - and by the application scope behind it, which holds
 * it where no context of the scope is registered.
 */
class CustomNormalScopeUnderGroovyTest {

    /**
     * A normal scope of the application's own, which a portable extension may add a context for as the container
     * starts.
     */
    @NormalScope
    @Inherited
    @Retention(RetentionPolicy.RUNTIME)
    @Target([ElementType.TYPE, ElementType.METHOD])
    static @interface Foggy {
    }

    @Foggy
    static class Fog {
        int id() {
            System.identityHashCode(this)
        }
    }

    static class Mist extends Fog {
    }

    static class Steam {
        int id() {
            System.identityHashCode(this)
        }
    }

    @Dependent
    static class SteamProducer {
        @Produces
        @Foggy
        Steam steam() {
            new Steam()
        }
    }

    @Foggy
    static class Cloud {
    }

    /**
     * Declares the dependent pseudo-scope over the normal scope it would inherit, and keeps state of its own.
     */
    @Dependent
    static class Drizzle extends Cloud {
        private int calls

        int count() {
            ++calls
        }
    }

    /**
     * A stereotype carrying the normal scope.
     */
    @Stereotype
    @Foggy
    @Retention(RetentionPolicy.RUNTIME)
    @Target([ElementType.TYPE, ElementType.METHOD])
    static @interface Hazy {
    }

    static class Ice {
    }

    @Dependent
    static class IceProducer {
        @Produces
        @Singleton
        @Hazy
        Ice ice() {
            new Ice()
        }
    }

    static class Puddle {
    }

    static class Pool {
    }

    /**
     * Declares a producer method and a producer field with no scope of their own in a normal scoped class.
     */
    @ApplicationScoped
    static class PuddleProducer {
        @Produces
        public static Pool pool

        @Produces
        Puddle puddle() {
            null
        }
    }

    @Test
    void aProducerWithNoScopeOfItsOwnIsDependentInANormalScopedClass() {
        try (ApplicationContext context = ApplicationContext.run()) {
            for (Class<?> type : [Puddle, Pool]) {
                def definition = context.getBeanDefinitions(type).find { it.hasAnnotation(CdiProducer) }
                assertEquals("jakarta.enterprise.context.Dependent",
                    definition.annotationMetadata.stringValue(CdiScope).orElse(null), type.name)
                assertEquals(false, definition.annotationMetadata.booleanValue(CdiScope, "normal").orElse(true),
                    type.name)
            }
        }
    }

    @Test
    void theScopesOwnAnnotationLeadsTheApplicationScope() {
        try (ApplicationContext context = ApplicationContext.run()) {
            for (Class<?> type : [Fog, Steam]) {
                def definition = context.findProxyTargetBeanDefinition(type, null).orElseThrow()
                assertEquals(Foggy.name, definition.scopeName.orElse(null), type.name)
                assertEquals([Foggy.name, CdiApplicationScope.name],
                    definition.annotationMetadata.getAnnotationNamesByStereotype("jakarta.inject.Scope").take(2),
                    type.name)
            }
            // a subclass that declares no bean defining annotation is not discovered by the scope it inherits
            assertTrue(context.getBeanDefinitions(Mist).isEmpty())
            assertEquals(context.getBean(Steam).id(), context.getBean(Steam).id())
        }
    }

    @Test
    void aPseudoScopeWinsOverTheNormalScopeAndIsNotProxiedForIt() {
        try (ApplicationContext context = ApplicationContext.run()) {
            Drizzle drizzle = context.getBean(Drizzle)
            assertEquals(Drizzle, drizzle.getClass())
            assertEquals(1, drizzle.count())
            assertEquals(2, drizzle.count())
            Ice ice = context.getBean(Ice)
            assertEquals(Ice, ice.getClass())
            assertSame(ice, context.getBean(Ice))
        }
    }
}
