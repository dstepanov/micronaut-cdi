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
 * contextual it is handed, holding the instance and the creational context it was created with. Each container has
 * an instance of its own, and a test reads the one of its container.
 */
public class MapContext implements AlterableContext {

    private final Map<Contextual<?>, Object[]> store = new HashMap<>();
    private volatile boolean active = true;

    @Override
    public Class<? extends Annotation> getScope() {
        return MapScoped.class;
    }

    @Override
    @SuppressWarnings("unchecked")
    public synchronized <T> T get(Contextual<T> contextual, CreationalContext<T> creationalContext) {
        Object[] held = store.get(contextual);
        if (held != null) {
            return (T) held[0];
        }
        if (creationalContext == null) {
            return null;
        }
        T instance = contextual.create(creationalContext);
        store.put(contextual, new Object[] {instance, creationalContext});
        return instance;
    }

    @Override
    @SuppressWarnings("unchecked")
    public synchronized <T> T get(Contextual<T> contextual) {
        Object[] held = store.get(contextual);
        return held == null ? null : (T) held[0];
    }

    @Override
    public boolean isActive() {
        return active;
    }

    /**
     * Switches the context on or off, for a test that asks about an inactive context.
     *
     * @param active Whether the context is active
     */
    public void setActive(boolean active) {
        this.active = active;
    }

    /**
     * How many instances the context holds.
     *
     * @return The number of instances
     */
    public synchronized int size() {
        return store.size();
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void destroy(Contextual<?> contextual) {
        Object[] held;
        synchronized (this) {
            held = store.remove(contextual);
        }
        if (held != null) {
            ((Contextual) contextual).destroy(held[0], (CreationalContext) held[1]);
        }
    }

    /**
     * Ends the context: destroys everything it holds, as a context does when it ends.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void endAll() {
        List<Map.Entry<Contextual<?>, Object[]>> entries;
        synchronized (this) {
            entries = new ArrayList<>(store.entrySet());
            store.clear();
        }
        for (Map.Entry<Contextual<?>, Object[]> entry : entries) {
            ((Contextual) entry.getKey()).destroy(entry.getValue()[0], (CreationalContext) entry.getValue()[1]);
        }
    }
}
