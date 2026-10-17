package io.micronaut.cdi.test.graalvm.hidden;

import java.time.Instant;

/**
 * The class of an event whose generic supertype is package-private. It is no bean: what it is a {@code Signal}
 * of is known from its type hierarchy record alone.
 */
public final class Tick extends Clock<Instant> {
}
