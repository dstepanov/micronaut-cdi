package io.micronaut.cdi.microprofile.context;

import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.Discovery;
import jakarta.enterprise.inject.build.compatible.spi.ScannedClasses;

/** Registers the adapter's already compiled beans when the application is compiled. */
public final class ContextBuildExtension implements BuildCompatibleExtension {
    @Discovery
    public void discover(ScannedClasses classes) {
        classes.add(ContextAccess.class.getName());
        classes.add(ContextProducers.class.getName());
    }
}
