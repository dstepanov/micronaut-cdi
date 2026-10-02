/* Licensed under the Apache License, Version 2.0. */
package org.example.cdi.synthetic;

import io.micronaut.cdi.internal.metadata.CdiRecordedType;
import io.micronaut.cdi.internal.metadata.CdiSyntheticBean;
import io.micronaut.context.annotation.Factory;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticBeanCreator;
import jakarta.inject.Singleton;

/** A recorded creator belonging to an explicitly selected application archive. */
@Factory
public class SelectedArrayFactory {
    @Singleton
    @CdiSyntheticBean(id = "selected-array-archive", implementation = Object.class,
        types = String[][].class, typeRecords = @CdiRecordedType(value = String.class, dimensions = 2))
    public SyntheticBeanCreator<Object> creator() {
        return (lookup, parameters) -> new String[][]{{"selected"}};
    }
}
