/*
 * Copyright 2017-2026 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.cdi.test.kotlin

import io.micronaut.cdi.internal.runtime.CdiBeanContainer
import io.micronaut.context.ApplicationContext
import io.micronaut.core.annotation.AnnotationValue
import jakarta.enterprise.context.Dependent
import jakarta.enterprise.util.Nonbinding
import jakarta.inject.Qualifier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A qualifier annotated with a second qualifier, which is annotated with the first.
 */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
@Sunset
annotation class Sunrise(@get:Nonbinding val hour: String = "")

/**
 * The second of two qualifiers annotated with each other.
 */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
@Sunrise
annotation class Sunset(@get:Nonbinding val hour: String = "")

/**
 * A bean qualified by the first of the two.
 */
@Dependent
@Sunrise(hour = "six")
open class Horizon

/**
 * Two qualifier interfaces annotated with each other, compiled through KSP, which hands a remapper an occurrence
 * without its meta-annotations: reading one interface reads the annotations on it, which name the other, whose
 * annotations name the first. The compilation has to end, and the bean has to resolve by a value of the
 * non-binding member it was not given.
 */
class MutuallyAnnotatedQualifiersUnderKspTest {

    @Test
    fun qualifiersThatNameEachOtherCompileAndResolve() {
        ApplicationContext.run().use { context ->
            val horizon = context.getBean(CdiBeanContainer::class.java).createInstance()
                .select(Horizon::class.java, AnnotationValue.builder(Sunrise::class.java).member("hour", "seven").build())
            assertEquals(Horizon::class.java, horizon.get().javaClass)
        }
    }
}
