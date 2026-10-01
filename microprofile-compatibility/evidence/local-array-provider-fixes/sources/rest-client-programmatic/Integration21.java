package org.example.mp.generated;
import jakarta.enterprise.context.*;
import jakarta.enterprise.inject.*;
import jakarta.inject.*;
import org.eclipse.microprofile.config.*;
import org.eclipse.microprofile.config.inject.*;
import java.util.*;
public class Integration21 {
@org.eclipse.microprofile.rest.client.inject.RegisterRestClient
@jakarta.ws.rs.Path("/ping")
public interface Remote extends AutoCloseable {
    @jakarta.ws.rs.GET String ping();
    void close();
}
@Dependent public static class Consumer {
    public String run() throws Exception {
        try(Remote remote=org.eclipse.microprofile.rest.client.RestClientBuilder.newBuilder().baseUri(java.net.URI.create(System.getProperty("mp.compat.base"))).build(Remote.class)){return remote.ping();}
    }
}

}