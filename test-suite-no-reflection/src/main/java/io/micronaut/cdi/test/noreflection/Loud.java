package io.micronaut.cdi.test.noreflection;

import jakarta.inject.Qualifier;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * A qualifier of the application the synthetic observer of {@link Whisper} observes with.
 */
@Qualifier
@Retention(RetentionPolicy.RUNTIME)
public @interface Loud {
}
