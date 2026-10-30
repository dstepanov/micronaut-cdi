package io.micronaut.cdi.test.customscope;

import io.micronaut.cdi.internal.extension.ExtensionContexts;
import io.micronaut.cdi.test.extension.mapscope.MapContext;
import io.micronaut.cdi.test.extension.mapscope.MapScoped;
import io.micronaut.context.BeanContext;

/**
 * The context of {@link MapScoped} a container registered, which a test reads what the context holds from.
 */
public final class MapContexts {

    private MapContexts() {
    }

    /**
     * The context of the scope the given container registered.
     *
     * @param beanContext The context of the container
     * @return The map context
     */
    public static MapContext of(BeanContext beanContext) {
        return (MapContext) beanContext.getBean(ExtensionContexts.class).contextsFor(MapScoped.class).get(0);
    }
}
