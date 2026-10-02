/* Licensed under the Apache License, Version 2.0. */
package io.micronaut.cdi.mptck;

import io.micronaut.context.ApplicationContext;
import jakarta.enterprise.inject.se.SeContainer;
import jakarta.enterprise.context.control.RequestContextController;
import java.net.URI;

/** One deployment at a time, matching the serial Arquillian lifecycle. */
public final class CurrentDeployment {
    static SeContainer container;
    static ApplicationContext context;
    static RequestContextController request;
    static ArchiveClassLoader loader;
    static ClassLoader previousLoader;
    static java.nio.file.Path evidence;
    static final java.util.List<AutoCloseable> endpoints = new java.util.ArrayList<>();
    static org.eclipse.microprofile.config.Config config;
    static org.eclipse.microprofile.config.spi.ConfigProviderResolver configResolver;
    static org.eclipse.microprofile.config.spi.ConfigProviderResolver previousConfigResolver;
    static URI uri;

    public static ApplicationContext context() {
        if (context == null) throw new IllegalStateException("No MicroProfile TCK deployment is active");
        return context;
    }
    public static <T> T bean(Class<T> type) {
        return container.select(type).get();
    }
}
