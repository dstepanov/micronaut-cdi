package io.micronaut.cdi.test.groovy.hidden

/**
 * An event payload, no bean, whose supertype is package-private.
 */
class HiddenPayload extends HiddenBase<Integer> {
}
