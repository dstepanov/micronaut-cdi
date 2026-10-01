package org.example.mp.generated;
import jakarta.enterprise.context.*;
import jakarta.enterprise.inject.*;
import jakarta.inject.*;
import org.eclipse.microprofile.config.*;
import org.eclipse.microprofile.config.inject.*;
import java.util.*;
public class Integration16 {
@io.micronaut.context.annotation.ClassImport(classes={io.smallrye.health.SmallRyeHealthReporter.class,io.smallrye.health.AsyncHealthCheckFactory.class})
public static class Imports {}
@ApplicationScoped @org.eclipse.microprofile.health.Liveness
public static class Live implements org.eclipse.microprofile.health.HealthCheck {
    public org.eclipse.microprofile.health.HealthCheckResponse call(){return org.eclipse.microprofile.health.HealthCheckResponse.up("live");}
}
@ApplicationScoped @org.eclipse.microprofile.health.Readiness
public static class Ready implements org.eclipse.microprofile.health.HealthCheck {
    public org.eclipse.microprofile.health.HealthCheckResponse call(){return org.eclipse.microprofile.health.HealthCheckResponse.up("ready");}
}
@Dependent public static class Consumer {
    @Inject io.smallrye.health.SmallRyeHealthReporter reporter;
    public String run(){return reporter.getLiveness().isDown()+":"+reporter.getReadiness().isDown()+":"+reporter.getHealth().isDown();}
}

}