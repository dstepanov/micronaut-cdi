package org.example.mp.generated;
import jakarta.enterprise.context.*;
import jakarta.enterprise.inject.*;
import jakarta.inject.*;
import org.eclipse.microprofile.config.*;
import org.eclipse.microprofile.config.inject.*;
import java.util.*;
public class Integration39 {
@io.micronaut.context.annotation.ClassImport(classes=io.smallrye.config.inject.ConfigProducer.class) public static class Imports {} @Dependent public static class Consumer {List<Integer> number;@Inject void initialize(@ConfigProperty(name="mp.compat.list") List<Integer> value){number=value;} public String run(){return ""+(number);}}
}