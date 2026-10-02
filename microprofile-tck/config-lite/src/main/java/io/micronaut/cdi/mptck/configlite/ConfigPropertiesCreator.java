/* Licensed under the Apache License, Version 2.0. */
package io.micronaut.cdi.mptck.configlite;

import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.build.compatible.spi.Parameters;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticBeanCreator;
import io.smallrye.config.SmallRyeConfig;

/** The external SmallRye mapping engine owns configuration-object creation and defaults. */
public final class ConfigPropertiesCreator implements SyntheticBeanCreator<Object> {
    @Override
    public Object create(Instance<Object> lookup, Parameters parameters) {
        return io.smallrye.config.Config.getOrCreate(Thread.currentThread().getContextClassLoader())
            .unwrap(SmallRyeConfig.class).getConfigMapping(parameters.get("class", Class.class),
                parameters.get("prefix", String.class));
    }
}
