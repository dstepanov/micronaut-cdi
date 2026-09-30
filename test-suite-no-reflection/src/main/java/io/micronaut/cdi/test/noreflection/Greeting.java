package io.micronaut.cdi.test.noreflection;

/**
 * A class no bean defining annotation is written on: the extension describes a bean of it.
 *
 * @param text   What the greeting says
 * @param volume How loudly
 * @param tone   In what tone
 */
public record Greeting(String text, int volume, Tone tone) {

    /**
     * The tone of a greeting.
     */
    public enum Tone {
        WARM, FORMAL
    }
}
