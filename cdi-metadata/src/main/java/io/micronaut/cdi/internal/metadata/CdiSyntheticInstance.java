/* Licensed under the Apache License, Version 2.0. */
package io.micronaut.cdi.internal.metadata;

import io.micronaut.core.annotation.Internal;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/** Describes the runtime bean, distinct from the factory's synthetic creation record. */
@Internal
@Retention(RetentionPolicy.RUNTIME)
public @interface CdiSyntheticInstance {
    /** @return The bean class declared through the build-compatible API */
    Class<?> beanClass();
    /** @return The already admitted factory that supplied this synthetic bean */
    Class<?> origin();
}
