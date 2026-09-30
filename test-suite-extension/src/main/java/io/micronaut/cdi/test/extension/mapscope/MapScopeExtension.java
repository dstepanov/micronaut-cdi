package io.micronaut.cdi.test.extension.mapscope;

import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.Discovery;
import jakarta.enterprise.inject.build.compatible.spi.MetaAnnotations;
import jakarta.enterprise.inject.build.compatible.spi.Parameters;
import jakarta.enterprise.inject.build.compatible.spi.Synthesis;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticBeanCreator;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticComponents;

/**
 * The extension of the map scope tests: a normal scope whose context is an ordinary map keyed by
 * the contextual, and two synthetic beans of one type and the same qualifiers.
 */
public final class MapScopeExtension implements BuildCompatibleExtension {

    /**
     * Registers the context of {@link MapScoped}.
     *
     * @param meta What the discovery phase may register
     */
    @Discovery
    public void mapScope(MetaAnnotations meta) {
        meta.addContext(MapScoped.class, true, MapContext.class);
    }

    /**
     * Two synthetic beans of the same type and the same (default) qualifiers: an ambiguous pair.
     *
     * @param components What the extension adds to the container
     */
    @Synthesis
    public void twins(SyntheticComponents components) {
        components.addBean(Twin.class)
            .type(Twin.class)
            .withParam("name", "one")
            .createWith(TwinCreator.class);
        components.addBean(Twin.class)
            .type(Twin.class)
            .withParam("name", "two")
            .createWith(TwinCreator.class);
    }

    /**
     * Creates a twin of the name it was described with.
     */
    public static final class TwinCreator implements SyntheticBeanCreator<Twin> {
        @Override
        public Twin create(Instance<Object> lookup, Parameters params) {
            return new Twin(params.get("name", String.class));
        }
    }
}
