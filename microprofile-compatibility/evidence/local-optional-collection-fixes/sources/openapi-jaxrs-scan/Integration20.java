package org.example.mp.generated;
import jakarta.enterprise.context.*;
import jakarta.enterprise.inject.*;
import jakarta.inject.*;
import org.eclipse.microprofile.config.*;
import org.eclipse.microprofile.config.inject.*;
import java.util.*;
public class Integration20 {
@jakarta.ws.rs.Path("/hello") public static class Resource {
    @jakarta.ws.rs.GET @org.eclipse.microprofile.openapi.annotations.Operation(operationId="hello")
    public String hello(){return "hello";}
}
@Dependent public static class Consumer {
    public String run() throws Exception {
        var api=io.smallrye.openapi.api.SmallRyeOpenAPI.builder()
            .withConfig(ConfigProvider.getConfig())
            .withIndex(org.jboss.jandex.Index.of(Resource.class))
            .enableStandardStaticFiles(false).enableStandardFilter(false).build();
        return api.model().getPaths().getPathItem("/hello").getGET().getOperationId();
    }
}

}