package io.micronaut.cdi.microprofile.context;

import io.micronaut.cdi.internal.context.RequestScope;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.annotation.PreDestroy;
import org.eclipse.microprofile.context.spi.ContextManagerProvider;
import org.eclipse.microprofile.context.spi.ContextManager;

/** Resolves the current container's request scope through an ordinary compiled CDI bean. */
@ApplicationScoped
public class ContextAccess {
    private final RequestScope requests;
    private final ContextManagerProvider provider;
    private final ContextManager manager;

    @Inject
    public ContextAccess(RequestScope requests) {
        this.requests = requests;
        this.provider = ContextManagerProvider.instance();
        this.manager = provider.getContextManager(Thread.currentThread().getContextClassLoader());
    }

    public RequestScope requests() {
        return requests;
    }

    @PreDestroy
    void releaseManager() {
        provider.releaseContextManager(manager);
    }
}
