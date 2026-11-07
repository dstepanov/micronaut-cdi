package io.micronaut.cdi.test.extension.kept;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Marks a class whose bean and observer {@link KeptDescriptionsExtension} keeps from the registration phase.
 */
@Retention(RetentionPolicy.RUNTIME)
public @interface KeptDescription {
}
