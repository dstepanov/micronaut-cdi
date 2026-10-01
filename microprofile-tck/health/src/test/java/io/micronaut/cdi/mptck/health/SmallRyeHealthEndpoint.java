/* Licensed under the Apache License, Version 2.0. */
package io.micronaut.cdi.mptck.health;

import io.micronaut.cdi.mptck.*;
import io.smallrye.health.*;
import java.util.Set;

public final class SmallRyeHealthEndpoint implements EndpointAdapter {
    @Override public AutoCloseable start(Set<String> classes, ClassLoader loader) throws Exception {
        SmallRyeHealthReporter reporter = CurrentDeployment.bean(SmallRyeHealthReporter.class);
        return new HttpEndpoint(loader, exchange -> {
            SmallRyeHealth health = switch (exchange.getRequestURI().getPath()) {
                case "/health" -> reporter.getHealth();
                case "/health/live" -> reporter.getLiveness();
                case "/health/ready" -> reporter.getReadiness();
                case "/health/started" -> reporter.getStartup();
                default -> null;
            };
            if (health == null) { HttpEndpoint.respond(exchange, 404, "text/plain", "Unknown endpoint"); return; }
            HttpEndpoint.respond(exchange, health.isDown() ? 503 : 200, "application/json", health.getPayload().toString());
        });
    }
}
