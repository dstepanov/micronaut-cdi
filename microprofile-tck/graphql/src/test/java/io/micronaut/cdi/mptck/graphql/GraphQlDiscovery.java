/* Licensed under the Apache License, Version 2.0. */
package io.micronaut.cdi.mptck.graphql;

import jakarta.enterprise.inject.build.compatible.spi.*;
import jakarta.enterprise.context.Dependent;
import org.eclipse.microprofile.graphql.GraphQLApi;

/** A minimal Lite integration: make GraphQL API classes discovered dependent beans at compilation. */
public final class GraphQlDiscovery implements BuildCompatibleExtension {
    @Discovery
    public void discover(ScannedClasses classes) {
        classes.add("org.eclipse.microprofile.graphql.tck.apps.basic.api.ScalarTestApi");
        classes.add("org.eclipse.microprofile.graphql.tck.apps.superhero.api.HeroFinder");
    }
    @Enhancement(types = Object.class, withAnnotations = GraphQLApi.class)
    public void api(ClassConfig type) {
        type.addAnnotation(Dependent.class);
    }
}
