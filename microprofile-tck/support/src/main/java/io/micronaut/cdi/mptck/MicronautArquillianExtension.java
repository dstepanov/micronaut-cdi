/* Licensed under the Apache License, Version 2.0. */
package io.micronaut.cdi.mptck;

import org.jboss.arquillian.container.spi.client.container.DeployableContainer;
import org.jboss.arquillian.core.spi.LoadableExtension;
import org.jboss.arquillian.test.spi.TestEnricher;
import org.jboss.arquillian.core.api.annotation.Observes;
import org.jboss.arquillian.test.spi.event.suite.Before;
import org.jboss.arquillian.test.spi.event.suite.After;

public final class MicronautArquillianExtension implements LoadableExtension {
    @Override public void register(ExtensionBuilder builder) {
        builder.service(DeployableContainer.class, MicronautDeployableContainer.class);
        builder.service(TestEnricher.class, MicronautTestEnricher.class);
        builder.service(org.jboss.arquillian.test.spi.enricher.resource.ResourceProvider.class, UriProvider.class);
        builder.service(org.jboss.arquillian.test.spi.enricher.resource.ResourceProvider.class, UrlProvider.class);
        builder.observer(RequestLifecycle.class);
    }
    public static final class RequestLifecycle {
        private final ThreadLocal<Boolean> activated = ThreadLocal.withInitial(() -> false);
        public void before(@Observes Before event) {
            if (CurrentDeployment.request != null) activated.set(CurrentDeployment.request.activate());
        }
        public void after(@Observes After event) {
            try { MicronautTestEnricher.releaseParameters(); }
            finally {
                try { if (activated.get() && CurrentDeployment.request != null) CurrentDeployment.request.deactivate(); }
                finally { activated.remove(); }
            }
        }
    }
}
