package io.micronaut.cdi.test.kotlin

import io.micronaut.cdi.internal.metadata.CdiApplicationScope
import io.micronaut.context.ApplicationContext
import jakarta.enterprise.context.Dependent
import jakarta.enterprise.context.NormalScope
import jakarta.enterprise.inject.Produces
import org.junit.jupiter.api.Assertions.assertEquals
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
}
