package org.example.mp.generated;
import jakarta.enterprise.context.*;
import jakarta.enterprise.inject.*;
import jakarta.inject.*;
import org.eclipse.microprofile.config.*;
import org.eclipse.microprofile.config.inject.*;
import java.util.*;
public class Integration32 {
@Qualifier @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
@java.lang.annotation.Target({java.lang.annotation.ElementType.FIELD,java.lang.annotation.ElementType.PARAMETER,java.lang.annotation.ElementType.METHOD})
public @interface Key { @jakarta.enterprise.util.Nonbinding String value() default ""; }
public static class Payload { final jakarta.enterprise.inject.spi.InjectionPoint point;
    Payload(jakarta.enterprise.inject.spi.InjectionPoint point) { this.point=point; } }
@Dependent public static class Producer {
    @Produces @Key Payload payload(jakarta.enterprise.inject.spi.InjectionPoint point) { return new Payload(point); }
}
@Dependent public static class Consumer {
    @Inject @Key("field") Provider<Payload> field;
    final Provider<Payload> constructor;
    @Inject public Consumer(@Key("ctor") Provider<Payload> constructor) { this.constructor=constructor; }
    static String describe(Payload payload) {
        var point=payload.point;
        String member=point.getMember() instanceof java.lang.reflect.Constructor ? "ctor" : point.getMember().getName();
        String key=point.getQualifiers().stream().filter(Key.class::isInstance).map(Key.class::cast).findFirst().orElseThrow().value();
        return ((Class<?>) point.getType()).getSimpleName()+":"+member+":"+key+":"+(point.getBean().getBeanClass()==Consumer.class);
    }
    public String run(){return describe(field.get())+"|"+describe(constructor.get());}
}

}