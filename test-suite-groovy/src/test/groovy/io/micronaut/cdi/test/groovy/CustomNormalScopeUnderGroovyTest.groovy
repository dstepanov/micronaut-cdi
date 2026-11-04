package io.micronaut.cdi.test.groovy

import io.micronaut.cdi.internal.metadata.CdiApplicationScope
import io.micronaut.context.ApplicationContext
import jakarta.enterprise.context.Dependent
import jakarta.enterprise.context.NormalScope
import jakarta.enterprise.inject.Produces
import org.junit.jupiter.api.Test

import java.lang.annotation.ElementType
import java.lang.annotation.Inherited
import java.lang.annotation.Retention
import java.lang.annotation.RetentionPolicy
import java.lang.annotation.Target

import static org.junit.jupiter.api.Assertions.assertEquals

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

    @Test
    void theScopesOwnAnnotationLeadsTheApplicationScope() {
        try (ApplicationContext context = ApplicationContext.run()) {
            for (Class<?> type : [Fog, Mist, Steam]) {
                def definition = context.findProxyTargetBeanDefinition(type, null).orElseThrow()
                assertEquals(Foggy.name, definition.scopeName.orElse(null), type.name)
                assertEquals([Foggy.name, CdiApplicationScope.name],
                    definition.annotationMetadata.getAnnotationNamesByStereotype("jakarta.inject.Scope").take(2),
                    type.name)
            }
            assertEquals(context.getBean(Mist).id(), context.getBean(Mist).id())
            assertEquals(context.getBean(Steam).id(), context.getBean(Steam).id())
        }
    }
}
