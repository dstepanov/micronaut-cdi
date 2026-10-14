package io.micronaut.cdi.test.groovy.hidden

import groovy.transform.PackageScope

/**
 * A generic supertype only its own package can name, which the record of a class extending it refers to.
 */
@PackageScope
abstract class HiddenBase<T> {
}
