package io.micronaut.cdi.test.kotlin

import io.micronaut.cdi.test.extension.Recorded
import io.micronaut.cdi.test.extension.Tier
import jakarta.enterprise.context.Dependent

/**
 * A Kotlin class carrying a Java annotation with an enum member, whose members KSP answers as properties.
 */
@Recorded
@Dependent
@Tier(Tier.Level.GOLD)
open class Graded
