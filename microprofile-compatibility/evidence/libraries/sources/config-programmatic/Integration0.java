package org.example.mp.generated;
import jakarta.enterprise.context.*;
import jakarta.enterprise.inject.*;
import jakarta.inject.*;
import org.eclipse.microprofile.config.*;
import org.eclipse.microprofile.config.inject.*;
import java.util.*;
public class Integration0 {
@Dependent public static class Consumer { public String run(){return ""+(ConfigProvider.getConfig().getValue("mp.compat.number",Integer.class));}}
}