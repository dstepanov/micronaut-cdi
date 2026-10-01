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
public class Mutation42 {
@InterceptorBinding @Retention(RetentionPolicy.RUNTIME) @java.lang.annotation.Target({ElementType.TYPE,ElementType.METHOD,ElementType.CONSTRUCTOR}) public @interface Bound {} @Dependent @Bound public static class Consumer { @AroundConstruct Object a(InvocationContext c)throws Exception{return c.proceed();} public String run(){return "target";} }
}