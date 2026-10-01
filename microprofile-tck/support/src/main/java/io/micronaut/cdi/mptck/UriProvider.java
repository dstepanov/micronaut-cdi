/* Licensed under the Apache License, Version 2.0. */
package io.micronaut.cdi.mptck;

import org.jboss.arquillian.test.api.ArquillianResource;
import org.jboss.arquillian.test.spi.enricher.resource.ResourceProvider;
import java.lang.annotation.Annotation;
import java.net.URI;

/** Supplies the deployment's actual loopback address to client-side upstream tests. */
public final class UriProvider implements ResourceProvider {
    @Override public boolean canProvide(Class<?> type) { return type == URI.class; }
    @Override public Object lookup(ArquillianResource resource, Annotation... annotations) {
        return System.getProperty("mp.tck.component").equals("graphql")
            ? URI.create(CurrentDeployment.uri + "/") : CurrentDeployment.uri;
    }
}
