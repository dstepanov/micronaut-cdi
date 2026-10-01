/* Licensed under the Apache License, Version 2.0. */
package io.micronaut.cdi.mptck;

import org.jboss.arquillian.test.api.ArquillianResource;
import org.jboss.arquillian.test.spi.enricher.resource.ResourceProvider;
import java.lang.annotation.Annotation;
import java.net.URL;

public final class UrlProvider implements ResourceProvider {
    @Override public boolean canProvide(Class<?> type) { return type == URL.class; }
    @Override public Object lookup(ArquillianResource resource, Annotation... annotations) {
        try { return java.net.URI.create(CurrentDeployment.uri + "/").toURL(); }
        catch (java.net.MalformedURLException e) { throw new IllegalStateException(e); }
    }
}
