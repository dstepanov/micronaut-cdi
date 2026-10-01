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
public class Mutation67 {
public static int count; @InterceptorBinding @Retention(RetentionPolicy.RUNTIME) @java.lang.annotation.Target({ElementType.TYPE,ElementType.METHOD,ElementType.CONSTRUCTOR}) public @interface Key {} @Key @jakarta.interceptor.Interceptor @Priority(100) public static class A {@AroundInvoke Object invoke(InvocationContext c)throws Exception{try{c.proceed();}catch(IllegalArgumentException ignored){}return c.proceed();}} @Key @jakarta.interceptor.Interceptor @Priority(200) public static class B {@AroundInvoke Object invoke(InvocationContext c)throws Exception{return "B"+c.proceed();}} @Dependent @Key public static class Consumer {public String run(){count++;return "target"+count;}}
}