package org.example.mp.generated;
import jakarta.enterprise.context.*;
import jakarta.enterprise.inject.*;
import jakarta.inject.*;
import org.eclipse.microprofile.config.*;
import org.eclipse.microprofile.config.inject.*;
import java.util.*;
public class Integration24 {
@io.micronaut.context.annotation.ClassImport(classes=io.smallrye.config.inject.ConfigProducer.class) public static class Imports {} @Dependent public static class Producer {
    @Produces io.helidon.config.Config helidonConfig(){return io.helidon.config.Config.empty();}
}
@ApplicationScoped public static class Consumer {
    @io.opentelemetry.instrumentation.annotations.WithSpan("compatibility")
    public String run(){return "span="+io.opentelemetry.api.trace.Span.current().getSpanContext().isValid();}
}

}