package io.micronaut.cdi.test.graalvm.hidden;

import io.micronaut.cdi.test.graalvm.Signal;

/**
 * A package-private supertype: the record of a class below it refers to it, so the record is written in this
 * package, and is found from there.
 *
 * @param <T> What a clock signals
 */
abstract class Clock<T> implements Signal<T> {
}
