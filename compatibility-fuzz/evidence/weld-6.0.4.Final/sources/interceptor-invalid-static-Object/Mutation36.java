package org.example.cdi.fuzz.generated;
import jakarta.enterprise.context.*;
import jakarta.enterprise.event.*;
import jakarta.enterprise.inject.*;
import jakarta.enterprise.inject.spi.*;
import jakarta.enterprise.util.*;
import jakarta.inject.*;
import jakarta.annotation.*;
import jakarta.interceptor.*;
import java.lang.annotation.*;
import java.util.*;
public class Mutation36 {
@InterceptorBinding @Retention(RetentionPolicy.RUNTIME) @java.lang.annotation.Target({ElementType.TYPE,ElementType.METHOD,ElementType.CONSTRUCTOR}) public @interface Bound {} @Bound @jakarta.interceptor.Interceptor @Priority(100) public static class I {@AroundInvoke public static Object around(InvocationContext c){return null;}} @Dependent @Bound public static class Consumer {public String run(){return "target";}}
}