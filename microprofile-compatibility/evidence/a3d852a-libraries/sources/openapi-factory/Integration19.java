package org.example.mp.generated;
import jakarta.enterprise.context.*;
import jakarta.enterprise.inject.*;
import jakarta.inject.*;
import org.eclipse.microprofile.config.*;
import org.eclipse.microprofile.config.inject.*;
import java.util.*;
public class Integration19 {
@Dependent public static class Consumer { public String run(){return ""+(org.eclipse.microprofile.openapi.OASFactory.createOpenAPI().info(org.eclipse.microprofile.openapi.OASFactory.createInfo().title("compat").version("1")).getInfo().getTitle());}}
}