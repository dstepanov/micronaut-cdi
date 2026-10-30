package io.micronaut.cdi.test.noreflection;

/**
 * An event a synthetic observer observes with a qualifier of the application, which has no generated annotation
 * builder: notifying the observer directly needs no instance of the qualifier.
 *
 * @param payload What the event carries
 */
public record Whisper(String payload) {
}
