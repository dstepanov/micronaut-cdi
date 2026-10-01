/* Licensed under the Apache License, Version 2.0. */
package io.micronaut.cdi.mptck;

import io.micronaut.cdi.internal.runtime.CurrentInjectionPoint;
import jakarta.enterprise.context.spi.CreationalContext;
import jakarta.enterprise.inject.spi.InjectionPoint;
import org.jboss.arquillian.test.spi.TestEnricher;
import org.jboss.arquillian.test.api.ArquillianResource;
import java.lang.reflect.*;
import java.util.*;

/** Enrich unmanaged test instances through CDI, preserving metadata and owning dependent instances. */
public final class MicronautTestEnricher implements TestEnricher {
    private static final Map<Object, CreationalContext<?>> OWNERS = new IdentityHashMap<>();
    private static final List<CreationalContext<?>> PARAMETERS = new ArrayList<>();

    @Override public void enrich(Object test) {
        if (CurrentDeployment.container == null) return;
        CreationalContext<?> owner = CurrentDeployment.container.getBeanManager().createCreationalContext(null);
        CreationalContext<?> previous = OWNERS.put(test, owner);
        if (previous != null) previous.release();
        try {
            List<Class<?>> hierarchy = new ArrayList<>();
            for (Class<?> c = test.getClass(); c != null && c != Object.class; c = c.getSuperclass()) hierarchy.add(c);
            Collections.reverse(hierarchy);
            for (Class<?> c : hierarchy) {
                for (Field field : c.getDeclaredFields()) {
                    boolean inject = field.isAnnotationPresent(jakarta.inject.Inject.class);
                    boolean resource = field.isAnnotationPresent(ArquillianResource.class);
                    if (!inject && !resource) continue;
                    if (Modifier.isStatic(field.getModifiers())) throw new IllegalArgumentException("Static TCK injection point " + field);
                    Object value = resource ? resource(field.getType()) : bean(TestInjectionPoint.of(field), owner);
                    if (value == null && resource) continue; // Allow other Arquillian resource providers to participate.
                    field.setAccessible(true);
                    field.set(test, value);
                }
                for (Method method : c.getDeclaredMethods()) {
                    if (!method.isAnnotationPresent(jakarta.inject.Inject.class) || method.isBridge() || method.isSynthetic()) continue;
                    if (Modifier.isStatic(method.getModifiers())) throw new IllegalArgumentException("Static TCK initializer " + method);
                    if (overridden(method, test.getClass())) continue;
                    method.setAccessible(true);
                    method.invoke(test, arguments(method, owner));
                }
            }
        } catch (Throwable failure) {
            OWNERS.remove(test);
            try { owner.release(); } catch (Throwable release) { failure.addSuppressed(release); }
            throw new IllegalStateException("Cannot enrich TCK test " + test.getClass().getName(), failure);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    static Object bean(InjectionPoint point, CreationalContext<?> owner) {
        // Programmatic BeanManager lookup has no generated resolution path. The production CDI metadata
        // stack supplies the requesting member to producers, including a deferred Instance/Provider.
        CurrentInjectionPoint.enter(point);
        try {
            if (CurrentDeployment.context == null) return CurrentDeployment.container.getBeanManager().getInjectableReference(point, owner);
            var qualifiers = point.getQualifiers().stream().map(io.micronaut.inject.qualifiers.Qualifiers::byAnnotation)
                .toArray(io.micronaut.context.Qualifier[]::new);
            io.micronaut.context.Qualifier qualifier = qualifiers.length == 0 ? null : qualifiers.length == 1 ? qualifiers[0]
                : io.micronaut.inject.qualifiers.Qualifiers.byQualifiers(qualifiers);
            // Preserve the selected generic argument for built-ins such as Provider<T>. Bean.create()
            // alone exposes only the built-in definition's generic type, not this requesting argument.
            var registration = CurrentDeployment.context.getBeanRegistration((io.micronaut.core.type.Argument) io.micronaut.core.type.Argument.of(point.getType()), qualifier);
            if (io.micronaut.cdi.internal.runtime.CdiResolution.isDependent(registration.getBeanDefinition())) {
                ((io.micronaut.cdi.internal.runtime.CdiCreationalContext<?>) owner).track(registration);
            }
            return registration.bean();
        }
        finally { CurrentInjectionPoint.leave(); }
    }

    static Object resource(Class<?> type) throws java.net.MalformedURLException {
        if (type != java.net.URI.class && type != java.net.URL.class) return null;
        if (CurrentDeployment.uri == null) throw new IllegalStateException("Deployment has no HTTP base URI");
        java.net.URI uri = CurrentDeployment.uri;
        if (type == java.net.URL.class || "graphql".equals(System.getProperty("mp.tck.component"))) {
            uri = java.net.URI.create(uri.toString().endsWith("/") ? uri.toString() : uri + "/");
        }
        return type == java.net.URI.class ? uri : uri.toURL();
    }

    @Override public Object[] resolve(Method method) {
        if (CurrentDeployment.container == null) return new Object[method.getParameterCount()];
        CreationalContext<?> owner = CurrentDeployment.container.getBeanManager().createCreationalContext(null);
        try {
            Object[] values = arguments(method, owner);
            PARAMETERS.add(owner);
            return values;
        } catch (Throwable failure) {
            try { owner.release(); } catch (Throwable release) { failure.addSuppressed(release); }
            throw new IllegalStateException("Cannot enrich TCK method " + method, failure);
        }
    }

    private static Object[] arguments(Method method, CreationalContext<?> owner) throws java.net.MalformedURLException {
        Object[] values = new Object[method.getParameterCount()];
        for (int i = 0; i < values.length; i++) {
            Parameter parameter = method.getParameters()[i];
            values[i] = parameter.isAnnotationPresent(ArquillianResource.class)
                ? resource(parameter.getType()) : bean(TestInjectionPoint.of(method, i), owner);
        }
        return values;
    }

    private static boolean overridden(Method inherited, Class<?> leaf) {
        if (Modifier.isPrivate(inherited.getModifiers())) return false;
        for (Class<?> c = leaf; c != inherited.getDeclaringClass(); c = c.getSuperclass()) {
            for (Method declared : c.getDeclaredMethods()) {
                if (!declared.getName().equals(inherited.getName()) || !Arrays.equals(declared.getParameterTypes(), inherited.getParameterTypes())) continue;
                if (Modifier.isPrivate(declared.getModifiers()) || Modifier.isStatic(declared.getModifiers())) continue;
                int access = inherited.getModifiers();
                if (Modifier.isPublic(access) || Modifier.isProtected(access)
                    || c.getPackageName().equals(inherited.getDeclaringClass().getPackageName())) return true;
            }
        }
        return false;
    }

    static void releaseParameters() { List<CreationalContext<?>> owners = new ArrayList<>(PARAMETERS); PARAMETERS.clear(); release(owners); }
    static void releaseAll() {
        List<CreationalContext<?>> owners = new ArrayList<>(OWNERS.values());
        owners.addAll(PARAMETERS);
        OWNERS.clear(); PARAMETERS.clear();
        release(owners);
    }
    private static void release(List<CreationalContext<?>> owners) {
        RuntimeException failure = null;
        for (CreationalContext<?> owner : owners) {
            try { owner.release(); }
            catch (RuntimeException e) { if (failure == null) failure = e; else failure.addSuppressed(e); }
        }
        if (failure != null) throw failure;
    }
}
