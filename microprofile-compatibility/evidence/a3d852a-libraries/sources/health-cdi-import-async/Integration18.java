package org.example.mp.generated;
import jakarta.enterprise.context.*;
import jakarta.enterprise.inject.*;
import jakarta.inject.*;
import org.eclipse.microprofile.config.*;
import org.eclipse.microprofile.config.inject.*;
import java.util.*;
public class Integration18 {
@io.micronaut.context.annotation.ClassImport(classes={io.smallrye.health.SmallRyeHealthReporter.class,io.smallrye.health.AsyncHealthCheckFactory.class})
public static class Imports {}
@ApplicationScoped @org.eclipse.microprofile.health.Liveness
public static class Live implements org.eclipse.microprofile.health.HealthCheck {
    public org.eclipse.microprofile.health.HealthCheckResponse call(){return org.eclipse.microprofile.health.HealthCheckResponse.up("live");}
}
@ApplicationScoped @org.eclipse.microprofile.health.Readiness
public static class Ready implements org.eclipse.microprofile.health.HealthCheck {
    public org.eclipse.microprofile.health.HealthCheckResponse call(){return org.eclipse.microprofile.health.HealthCheckResponse.down("ready");}
}
@Dependent public static class Consumer {
    @Inject io.smallrye.health.SmallRyeHealthReporter reporter;
    public String run(){return reporter.getLiveness().isDown()+":"+reporter.getReadiness().isDown()+":"+reporter.getHealth().isDown();}
}
@ApplicationScoped @org.eclipse.microprofile.health.Liveness
public static class Async implements io.smallrye.health.api.AsyncHealthCheck {
    public io.smallrye.mutiny.Uni<org.eclipse.microprofile.health.HealthCheckResponse> call(){return io.smallrye.mutiny.Uni.createFrom().item(org.eclipse.microprofile.health.HealthCheckResponse.down("async"));}
}

}