package io.micronaut.cdi.test.extension.signpost;

/**
 * An event the synthetic observer observes.
 *
 * @param traveller Who arrived
 */
public record Arrival(String traveller) {
}
