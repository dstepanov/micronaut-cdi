/* Licensed under the Apache License, Version 2.0. */
package org.example.cdi.synthetic;

import io.micronaut.cdi.internal.metadata.CdiRecordedType;
import io.micronaut.cdi.internal.metadata.CdiSyntheticBean;
import io.micronaut.context.annotation.Factory;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticBeanCreator;
import jakarta.inject.Singleton;

/** A second archive must not be admitted simply because its synthetic bean returns an array. */
@Factory
public class OmittedArrayFactory {
    @Singleton
    @CdiSyntheticBean(id = "omitted-array-archive", implementation = Object.class,
        types = int[][].class,
        typeRecords = @CdiRecordedType(kind = "PRIMITIVE", name = "int", dimensions = 2))
    public SyntheticBeanCreator<Object> creator() {
        return (lookup, parameters) -> new int[][]{{42}};
    }
}
