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
public class Mutation60 {
@InterceptorBinding @Retention(RetentionPolicy.RUNTIME) @java.lang.annotation.Target({ElementType.TYPE,ElementType.METHOD}) public @interface Key {String value(); @Nonbinding String note() default "";} @Key("a") @jakarta.interceptor.Interceptor @Priority(100) public static class A { @AroundInvoke Object around(InvocationContext ctx)throws Exception{return "A["+ctx.proceed()+"]";}} @Key("b") @jakarta.interceptor.Interceptor @Priority(200) public static class B { @AroundInvoke Object around(InvocationContext ctx)throws Exception{return "B["+ctx.proceed()+"]";}} @Key("a") public static class Base { @Key("b") public String run(){return "target";}} @Dependent public static class Consumer extends Base {@Override @Key("a") public String run(){return "target";}}
}