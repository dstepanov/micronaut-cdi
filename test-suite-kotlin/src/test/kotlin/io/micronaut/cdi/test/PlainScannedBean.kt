package io.micronaut.cdi.test

/**
 * A class with nothing on it, which the extension suite's discovery phase adds to the scanned classes.
 */
open class PlainScannedBean {

    /**
     * A nested class the extension adds by its binary name.
     */
    open class Nested
}
