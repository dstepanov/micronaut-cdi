/* Licensed under the Apache License, Version 2.0. */
package io.micronaut.cdi.mptck;

import java.util.Set;

/** Vendor-specific HTTP transport wiring; all feature behavior remains in the external implementation. */
public interface EndpointAdapter {
    AutoCloseable start(Set<String> classes, ClassLoader loader) throws Exception;
}
