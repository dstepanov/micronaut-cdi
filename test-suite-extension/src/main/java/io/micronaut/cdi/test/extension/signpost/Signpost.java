package io.micronaut.cdi.test.extension.signpost;

/**
 * A class no bean defining annotation is written on: the extension describes a bean of it.
 *
 * @param text What the signpost says
 */
public record Signpost(String text) {
}
