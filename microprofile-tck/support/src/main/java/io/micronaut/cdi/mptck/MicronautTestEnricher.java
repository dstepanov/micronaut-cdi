/* Licensed under the Apache License, Version 2.0. */
package io.micronaut.cdi.mptck;

import io.micronaut.core.type.Argument;
import io.micronaut.context.Qualifier;
import io.micronaut.inject.qualifiers.Qualifiers;
import org.jboss.arquillian.test.spi.TestEnricher;
import org.jboss.arquillian.test.api.ArquillianResource;
import java.lang.annotation.Annotation;
import java.lang.reflect.*;
import java.util.*;

/** Injects unmanaged test instances; a failed injection is reported immediately with its cause. */
public final class MicronautTestEnricher implements TestEnricher {
    @Override public void enrich(Object test) {
        if (CurrentDeployment.container == null) return;
        if (CurrentDeployment.context == null) injectReference(test);
        for (Class<?> c = test.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                boolean inject = field.isAnnotationPresent(jakarta.inject.Inject.class);
                boolean resource = field.isAnnotationPresent(ArquillianResource.class);
                if (!inject && !resource) continue;
                try {
                    Object value;
                    if (resource && field.getType() == java.net.URI.class) value = System.getProperty("mp.tck.component").equals("graphql")
                        ? java.net.URI.create(CurrentDeployment.uri + "/") : CurrentDeployment.uri;
                    else if (resource && field.getType() == java.net.URL.class) value = java.net.URI.create(CurrentDeployment.uri + "/").toURL();
                    else if (inject && CurrentDeployment.context != null) value = bean(field.getGenericType(), field.getAnnotations());
                    else continue;
                    field.setAccessible(true);
                    field.set(test, value);
                } catch (Exception failure) {
                    throw new IllegalStateException("Cannot enrich TCK injection point " + field, failure);
                }
            }
        }
    }
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void injectReference(Object test) {
        var manager = CurrentDeployment.container.getBeanManager();
        var target = manager.getInjectionTargetFactory(manager.createAnnotatedType(test.getClass())).createInjectionTarget(null);
        ((jakarta.enterprise.inject.spi.InjectionTarget) target).inject(test, manager.createCreationalContext(null));
    }
    @SuppressWarnings({"unchecked", "rawtypes"})
    static Object bean(Type type, Annotation[] annotations) {
        List<Qualifier<Object>> qualifiers = new ArrayList<>();
        for (Annotation a : annotations) {
            if (a.annotationType().isAnnotationPresent(jakarta.inject.Qualifier.class)
                && !(a instanceof jakarta.enterprise.inject.Any)) qualifiers.add(Qualifiers.byAnnotation(a));
        }
        Qualifier<Object> qualifier = qualifiers.isEmpty() ? null
            : qualifiers.size() == 1 ? qualifiers.get(0) : Qualifiers.byQualifiers(qualifiers.toArray(Qualifier[]::new));
        return CurrentDeployment.context().getBean((Argument) Argument.of(type), qualifier);
    }
    @Override public Object[] resolve(Method method) { return new Object[method.getParameterCount()]; }
}
