package org.example.mp.generated;
import jakarta.enterprise.context.*;
import jakarta.enterprise.inject.*;
import jakarta.inject.*;
import org.eclipse.microprofile.config.*;
import org.eclipse.microprofile.config.inject.*;
import java.util.*;
public class Integration43 {
@io.micronaut.context.annotation.ClassImport(classes=io.smallrye.config.inject.ConfigProducer.class) public static class Imports {} public static class Converted {final String text;public Converted(String text){this.text=text;}public String toString(){return "converted:"+text;}}@Dependent public static class Consumer {@Inject @ConfigProperty(name="mp.compat.number") Optional<Converted> number; public String run(){return ""+(number.orElseThrow());}}
}