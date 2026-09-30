package io.micronaut.cdi.test.extension.mapscope;

import jakarta.enterprise.context.spi.AlterableContext;
import jakarta.enterprise.context.spi.Contextual;
import jakarta.enterprise.context.spi.CreationalContext;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The context of {@link MapScoped}, written the way an application would: a {@link HashMap} keyed by the
 * contextual it is handed, holding the instance and the creational context it was created with.
 */
public class MapContext implements AlterableContext {

    /** Whether the context is active; a test switches it off to ask about an inactive context. */
    public static volatile boolean active = true;

    /** What the context holds, shared by every instance of the context so that a test can read it. */
    public static final Map<Contextual<?>, Object[]> STORE = new HashMap<>();

    @Override
    public Class<? extends Annotation> getScope() {
        return MapScoped.class;
    }

    @Override
    @SuppressWarnings("unchecked")
    public synchronized <T> T get(Contextual<T> contextual, CreationalContext<T> creationalContext) {
        Object[] held = STORE.get(contextual);
        if (held != null) {
            return (T) held[0];
        }
        if (creationalContext == null) {
            return null;
        }
        T instance = contextual.create(creationalContext);
        STORE.put(contextual, new Object[] {instance, creationalContext});
        return instance;
    }

    @Override
    @SuppressWarnings("unchecked")
    public synchronized <T> T get(Contextual<T> contextual) {
        Object[] held = STORE.get(contextual);
        return held == null ? null : (T) held[0];
    }

    @Override
    public boolean isActive() {
        return active;
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public synchronized void destroy(Contextual<?> contextual) {
        Object[] held = STORE.remove(contextual);
        if (held != null) {
            ((Contextual) contextual).destroy(held[0], (CreationalContext) held[1]);
        }
    }

    /**
     * Ends the context: destroys everything it holds, as a context does when it ends.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static synchronized void endAll() {
        List<Map.Entry<Contextual<?>, Object[]>> entries = new ArrayList<>(STORE.entrySet());
        STORE.clear();
        for (Map.Entry<Contextual<?>, Object[]> entry : entries) {
            ((Contextual) entry.getKey()).destroy(entry.getValue()[0], (CreationalContext) entry.getValue()[1]);
        }
    }
}
