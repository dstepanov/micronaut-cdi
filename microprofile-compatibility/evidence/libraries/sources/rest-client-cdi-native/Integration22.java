package org.example.mp.generated;
import jakarta.enterprise.context.*;
import jakarta.enterprise.inject.*;
import jakarta.inject.*;
import org.eclipse.microprofile.config.*;
import org.eclipse.microprofile.config.inject.*;
import java.util.*;
public class Integration22 {
@org.eclipse.microprofile.rest.client.inject.RegisterRestClient
@jakarta.ws.rs.Path("/ping")
public interface Remote extends AutoCloseable {
    @jakarta.ws.rs.GET String ping();
    void close();
}
@Dependent public static class Consumer {
    @Inject @org.eclipse.microprofile.rest.client.inject.RestClient Remote remote;
    public String run(){return remote.ping();}
}

}