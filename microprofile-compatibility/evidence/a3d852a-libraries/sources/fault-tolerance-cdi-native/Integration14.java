package org.example.mp.generated;
import jakarta.enterprise.context.*;
import jakarta.enterprise.inject.*;
import jakarta.inject.*;
import org.eclipse.microprofile.config.*;
import org.eclipse.microprofile.config.inject.*;
import java.util.*;
public class Integration14 {
@io.micronaut.context.annotation.ClassImport(classes=io.smallrye.config.inject.ConfigProducer.class) public static class Imports {} @ApplicationScoped public static class Consumer {
    int count;
    @org.eclipse.microprofile.faulttolerance.Retry(maxRetries=2,delay=0,jitter=0)
    public String run(){if(++count<3)throw new IllegalStateException("try again");return "attempts="+count;}
}

}