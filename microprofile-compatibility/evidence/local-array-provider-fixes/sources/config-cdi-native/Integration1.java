package org.example.mp.generated;
import jakarta.enterprise.context.*;
import jakarta.enterprise.inject.*;
import jakarta.inject.*;
import org.eclipse.microprofile.config.*;
import org.eclipse.microprofile.config.inject.*;
import java.util.*;
public class Integration1 {
@Dependent public static class Consumer {@Inject @ConfigProperty(name="mp.compat.number") int number; public String run(){return ""+(number);}}
}