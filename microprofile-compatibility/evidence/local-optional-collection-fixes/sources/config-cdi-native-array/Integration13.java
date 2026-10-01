package org.example.mp.generated;
import jakarta.enterprise.context.*;
import jakarta.enterprise.inject.*;
import jakarta.inject.*;
import org.eclipse.microprofile.config.*;
import org.eclipse.microprofile.config.inject.*;
import java.util.*;
public class Integration13 {
@Dependent public static class Consumer {@Inject @ConfigProperty(name="mp.compat.list") Integer[] values; public String run(){return ""+(Arrays.toString(values));}}
}