/* Licensed under the Apache License, Version 2.0. */
package io.micronaut.cdi.mptck;

/** Keeps the reference SE container alive for resource-only deployments which have no application beans. */
@jakarta.enterprise.context.Dependent
public class ReferenceAnchor {
}
