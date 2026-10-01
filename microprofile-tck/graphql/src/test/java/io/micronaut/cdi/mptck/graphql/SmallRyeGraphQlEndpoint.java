/* Licensed under the Apache License, Version 2.0. */
package io.micronaut.cdi.mptck.graphql;

import io.micronaut.cdi.mptck.*;
import io.smallrye.graphql.schema.SchemaBuilder;
import io.smallrye.graphql.bootstrap.Bootstrap;
import io.smallrye.graphql.execution.ExecutionService;
import org.jboss.jandex.Index;
import graphql.schema.idl.SchemaPrinter;
import jakarta.json.Json;
import java.util.*;

/** Builds and executes the schema with SmallRye; the adapter only supplies HTTP transport. */
public final class SmallRyeGraphQlEndpoint implements EndpointAdapter {
    @Override public AutoCloseable start(Set<String> classes, ClassLoader loader) throws Exception {
        Class<?>[] indexed = classes.stream().filter(s -> s.startsWith("org.eclipse.microprofile.graphql.tck.apps."))
            .map(s -> { try { return loader.loadClass(s); } catch (ClassNotFoundException e) { throw new IllegalStateException(e); } })
            .toArray(Class<?>[]::new);
        var schema = SchemaBuilder.build(Index.of(indexed));
        var graphql = Bootstrap.bootstrap(schema);
        var service = new ExecutionService(graphql, schema);
        String sdl = new SchemaPrinter().print(graphql);
        return new HttpEndpoint(loader, exchange -> {
            switch (exchange.getRequestURI().getPath()) {
                case "/graphql/schema.graphql" -> HttpEndpoint.respond(exchange, 200, "text/plain", sdl);
                case "/graphql" -> {
                    try (var reader = Json.createReader(exchange.getRequestBody())) {
                        var response = service.execute(reader.readObject());
                        HttpEndpoint.respond(exchange, 200, "application/json", response.getExecutionResultAsString());
                    }
                }
                default -> HttpEndpoint.respond(exchange, 404, "text/plain", "Unknown endpoint");
            }
        });
    }
}
