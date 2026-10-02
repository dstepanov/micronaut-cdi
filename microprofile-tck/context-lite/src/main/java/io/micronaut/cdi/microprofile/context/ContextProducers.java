package io.micronaut.cdi.microprofile.context;

import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.eclipse.microprofile.context.ThreadContext;

/** Standard default injection support; SmallRye still applies runtime MicroProfile Config defaults. */
@Dependent
public class ContextProducers {
    @Produces
    @Dependent
    public ThreadContext context() {
        return ThreadContext.builder().build();
    }

    @Produces
    @Dependent
    public ManagedExecutor executor() {
        return ManagedExecutor.builder().build();
    }

    public void destroy(@Disposes ManagedExecutor executor) {
        executor.shutdown();
    }
}
