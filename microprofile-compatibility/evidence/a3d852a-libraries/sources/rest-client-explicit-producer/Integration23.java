package org.example.mp.generated;
import jakarta.enterprise.context.*;
import jakarta.enterprise.inject.*;
import jakarta.inject.*;
import org.eclipse.microprofile.config.*;
import org.eclipse.microprofile.config.inject.*;
import java.util.*;
public class Integration23 {
@org.eclipse.microprofile.rest.client.inject.RegisterRestClient
@jakarta.ws.rs.Path("/ping")
public interface Remote extends AutoCloseable {
    @jakarta.ws.rs.GET String ping();
    void close();
}
@Dependent public static class Producer {
    @Produces @Dependent @org.eclipse.microprofile.rest.client.inject.RestClient
    Remote remote(){return org.eclipse.microprofile.rest.client.RestClientBuilder.newBuilder().baseUri(java.net.URI.create(System.getProperty("mp.compat.base"))).build(Remote.class);}
    void dispose(@Disposes @org.eclipse.microprofile.rest.client.inject.RestClient Remote remote){remote.close();}
}
@Dependent public static class Consumer {
    @Inject @org.eclipse.microprofile.rest.client.inject.RestClient Remote remote;
    public String run(){return remote.ping();}
}

}