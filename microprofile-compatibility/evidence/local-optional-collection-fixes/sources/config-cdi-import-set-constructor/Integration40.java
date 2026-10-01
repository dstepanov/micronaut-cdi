package org.example.mp.generated;
import jakarta.enterprise.context.*;
import jakarta.enterprise.inject.*;
import jakarta.inject.*;
import org.eclipse.microprofile.config.*;
import org.eclipse.microprofile.config.inject.*;
import java.util.*;
public class Integration40 {
@io.micronaut.context.annotation.ClassImport(classes=io.smallrye.config.inject.ConfigProducer.class) public static class Imports {} @Dependent public static class Consumer {Set<Integer> number;@Inject public Consumer(@ConfigProperty(name="mp.compat.list") Set<Integer> value){number=value;} public String run(){return ""+(new TreeSet<>(number));}}
}