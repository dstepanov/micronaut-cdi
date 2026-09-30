package io.micronaut.cdi.test.noreflection;

/**
 * An event the synthetic observer observes.
 *
 * @param payload What the event carries
 */
public record Ping(String payload) {
}
