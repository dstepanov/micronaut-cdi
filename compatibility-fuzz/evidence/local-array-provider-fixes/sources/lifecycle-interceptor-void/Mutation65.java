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
public class Mutation65 {
public static final List<String> LOG=new ArrayList<>(); @InterceptorBinding @Retention(RetentionPolicy.RUNTIME) @java.lang.annotation.Target({ElementType.TYPE,ElementType.METHOD,ElementType.CONSTRUCTOR}) public @interface Key {} @Key @jakarta.interceptor.Interceptor @Priority(100) public static class I {@AroundConstruct void construct(InvocationContext c)throws Exception{LOG.add("construct-before:"+(c.getTarget()==null));LOG.add("return-null:"+(c.proceed()==null));LOG.add("construct-after:"+(c.getTarget()!=null));return;} @PostConstruct void init(InvocationContext c)throws Exception{LOG.add("init-before");LOG.add("init-return-null:"+(c.proceed()==null));return;} @PreDestroy void destroy(InvocationContext c)throws Exception{LOG.add("destroy-before");c.proceed();return;}} @Dependent @Key public static class Target {@PostConstruct void init(){LOG.add("target-init");} @PreDestroy void destroy(){LOG.add("target-destroy");} public void ping(){LOG.add("ping");}} @Dependent public static class Consumer {@Inject Instance<Target> targets; public String run(){Target t=targets.get();t.ping();targets.destroy(t);return LOG.toString();}}
}