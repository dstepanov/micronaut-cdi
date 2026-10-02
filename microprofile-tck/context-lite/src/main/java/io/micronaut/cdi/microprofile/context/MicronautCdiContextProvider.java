package io.micronaut.cdi.microprofile.context;

import jakarta.enterprise.inject.spi.CDI;
import org.eclipse.microprofile.context.ThreadContext;
import org.eclipse.microprofile.context.spi.ThreadContextProvider;
import org.eclipse.microprofile.context.spi.ThreadContextSnapshot;
import java.util.Map;

/**
 * SmallRye SPI adapter for Micronaut's CDI Lite request context. Application and singleton contexts
 * already belong to the same container on every thread. Session, conversation and JTA are not supplied.
 */
public final class MicronautCdiContextProvider implements ThreadContextProvider {
    @Override
    public ThreadContextSnapshot currentContext(Map<String, String> properties) {
        var request = CDI.current().select(ContextAccess.class).get().requests().capture();
        return () -> request.begin()::close;
    }

    @Override
    public ThreadContextSnapshot clearedContext(Map<String, String> properties) {
        var request = CDI.current().select(ContextAccess.class).get().requests().captureCleared();
        return () -> request.begin()::close;
    }

    @Override
    public String getThreadContextType() {
        return ThreadContext.CDI;
    }
}
