/* Licensed under the Apache License, Version 2.0. */
package io.micronaut.cdi.mptck.configlite;

import io.smallrye.config.inject.ConfigProducerUtil;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.build.compatible.spi.Parameters;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticBeanCreator;
import jakarta.enterprise.inject.spi.InjectionPoint;

/** SmallRye performs conversion using the injection point supplied by CDI synthetic-bean creation. */
public final class ConfigValueCreator implements SyntheticBeanCreator<Object> {
    @Override
    public Object create(Instance<Object> lookup, Parameters parameters) {
        return ConfigProducerUtil.getValue(lookup.select(InjectionPoint.class).get(),
            io.smallrye.config.Config.getOrCreate(Thread.currentThread().getContextClassLoader()));
    }
}
