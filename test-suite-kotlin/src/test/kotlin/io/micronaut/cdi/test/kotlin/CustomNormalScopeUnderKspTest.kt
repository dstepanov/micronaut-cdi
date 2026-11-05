package io.micronaut.cdi.test.kotlin

import io.micronaut.cdi.internal.metadata.CdiApplicationScope
import io.micronaut.context.ApplicationContext
import jakarta.enterprise.context.Dependent
import jakarta.enterprise.context.NormalScope
import jakarta.enterprise.inject.Produces
import jakarta.enterprise.inject.Stereotype
import jakarta.inject.Singleton
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.lang.annotation.Inherited

/**
 * A normal scope of the application's own, which a portable extension may add a context for as the container starts.
 */
@NormalScope
@Inherited
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class Foggy

@Foggy
open class Fog {
    open fun id(): Int = System.identityHashCode(this)
}

open class Mist : Fog()

open class Steam {
    open fun id(): Int = System.identityHashCode(this)
}

@Dependent
open class SteamProducer {
    @Produces
    @Foggy
    open fun steam(): Steam = Steam()
}

@Foggy
open class Cloud

/**
 * Declares the dependent pseudo-scope over the normal scope it would inherit, and keeps state of its own.
 */
@Dependent
open class Drizzle : Cloud() {
    private var calls = 0

    open fun count(): Int = ++calls
}

/**
 * A stereotype carrying the normal scope.
 */
@Stereotype
@Foggy
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class Hazy

open class Ice

@Dependent
open class IceProducer {
    @Produces
    @Singleton
    @Hazy
    open fun ice(): Ice = Ice()
}

/**
 * A bean of a normal scope of the application's own compiled through KSP is held first by the scope's own
 * annotation - declared, inherited or declared by a producer - and by the application scope behind it, which holds
 * it where no context of the scope is registered.
 */
class CustomNormalScopeUnderKspTest {

    @Test
    fun theScopesOwnAnnotationLeadsTheApplicationScope() {
        ApplicationContext.run().use { context ->
            for (type in listOf(Fog::class.java, Mist::class.java, Steam::class.java)) {
                val definition = context.findProxyTargetBeanDefinition(type, null).orElseThrow()
                assertEquals(Foggy::class.java.name, definition.scopeName.orElse(null), type.name)
                assertEquals(
                    listOf(Foggy::class.java.name, CdiApplicationScope::class.java.name),
                    definition.annotationMetadata.getAnnotationNamesByStereotype("jakarta.inject.Scope").take(2),
                    type.name
                )
            }
            assertEquals(context.getBean(Mist::class.java).id(), context.getBean(Mist::class.java).id())
            assertEquals(context.getBean(Steam::class.java).id(), context.getBean(Steam::class.java).id())
        }
    }

    @Test
    fun aPseudoScopeWinsOverTheNormalScopeAndIsNotProxiedForIt() {
        ApplicationContext.run().use { context ->
            val drizzle = context.getBean(Drizzle::class.java)
            assertEquals(Drizzle::class.java, drizzle.javaClass)
            assertEquals(1, drizzle.count())
            assertEquals(2, drizzle.count())
            val ice = context.getBean(Ice::class.java)
            assertEquals(Ice::class.java, ice.javaClass)
            assertSame(ice, context.getBean(Ice::class.java))
        }
    }
}
