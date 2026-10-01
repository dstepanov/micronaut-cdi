/* Licensed under the Apache License, Version 2.0. */
package io.micronaut.cdi.mptck.openapi;

import io.micronaut.cdi.mptck.*;
import io.smallrye.openapi.api.SmallRyeOpenAPI;
import org.jboss.jandex.Index;
import org.eclipse.microprofile.config.ConfigProvider;
import java.util.*;

public final class SmallRyeOpenApiEndpoint implements EndpointAdapter {
    @Override public AutoCloseable start(Set<String> classes, ClassLoader loader) throws Exception {
        Class<?>[] indexed = classes.stream().filter(s -> s.startsWith("org.eclipse.microprofile.openapi"))
            .map(s -> { try { return loader.loadClass(s); } catch (ClassNotFoundException e) { throw new IllegalStateException(e); } })
            .toArray(Class<?>[]::new);
        SmallRyeOpenAPI api = SmallRyeOpenAPI.builder().withApplicationClassLoader(loader)
            .withIndex(Index.of(indexed)).withConfig(ConfigProvider.getConfig(loader)).build();
        return new HttpEndpoint(loader, exchange -> {
            if (!exchange.getRequestURI().getPath().equals("/openapi")) {
                HttpEndpoint.respond(exchange, 404, "text/plain", "Unknown endpoint"); return;
            }
            String accept = exchange.getRequestHeaders().getFirst("Accept");
            boolean json = accept != null && accept.contains("application/json");
            HttpEndpoint.respond(exchange, 200, json ? "application/json" : "application/yaml", json ? api.toJSON() : api.toYAML());
        });
    }
}
