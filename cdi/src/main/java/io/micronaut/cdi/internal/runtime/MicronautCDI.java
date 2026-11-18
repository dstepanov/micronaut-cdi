/*
 * Copyright 2017-2026 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.cdi.internal.runtime;

import io.micronaut.cdi.MicronautInstance;
import io.micronaut.context.Qualifier;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.type.Argument;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.BeanContainer;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.enterprise.util.TypeLiteral;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.util.Iterator;

/**
 * The static entry point of the specification, resolved to the container that is running.
 *
 * <p>It is a lookup of every bean in the container, which is what {@code CDI.current()} is: an
 * {@link Instance} of {@code Object} that a program narrows to what it is after. That part is delegated to
 * {@link CdiInstance}, which is the same lookup an injection point resolves through.</p>
 *
 * <p>The bean manager it is also asked for belongs to CDI Full, and the container answers as much of it as CDI
 * Lite can: the manager extends the {@link BeanContainer} that Lite is written in terms of, and the parts of it
 * that are about Lite are ones this container knows.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class MicronautCDI extends CDI<Object> implements MicronautInstance<Object> {

    private final CdiBeanContainer container;
    private final MicronautInstance<Object> lookup;

    MicronautCDI(CdiBeanContainer container) {
        this.container = container;
        this.lookup = new CdiInstance<>(container.beanContext(), Argument.OBJECT_ARGUMENT);
    }

    /**
     * The provider of this module the specification's entry point resolves through, which a container registers
     * with as it starts: the one the specification's API holds, or one installed with {@code CDI.setCDIProvider}
     * where the API holds none of this module. A provider a program set itself is left in place, and the container
     * is then registered with one of its own that the entry point does not reach. A provider the API found itself is
     * replaced: the API makes a new instance of each provider it finds, which holds no container, so only an installed
     * one reaches the containers of this module, and it hands over to the providers the API would find while none of
     * them runs ({@link MicronautCDIProvider#getCDI()}). The provider is read from the API's own record of it, which a
     * subclass of {@code CDI} sees, under the lock of the API class, so that two containers starting at once install
     * one provider.
     *
     * @return The provider
     */
    static MicronautCDIProvider installedProvider() {
        synchronized (CDI.class) {
            MicronautCDIProvider held = heldProvider();
            if (held != null) {
                return held;
            }
            ProviderState state = providerState.get();
            MicronautCDIProvider mine = new MicronautCDIProvider();
            if (state == null || state.provider() == null || !state.setManually()) {
                CDI.setCDIProvider(mine);
            }
            return mine;
        }
    }

    /**
     * The provider of this module the specification's API holds, read without installing one.
     *
     * @return The provider, or {@code null} where the API holds none of this module
     */
    static @Nullable MicronautCDIProvider heldProvider() {
        ProviderState state = providerState.get();
        return state != null && state.provider() instanceof MicronautCDIProvider mine ? mine : null;
    }

    @Override
    public BeanManager getBeanManager() {
        return container;
    }

    @Override
    public BeanContainer getBeanContainer() {
        return container;
    }

    @Override
    public MicronautInstance<Object> select(Annotation... qualifiers) {
        return lookup.select(qualifiers);
    }

    @Override
    public <U> MicronautInstance<U> select(Class<U> subtype, Annotation... qualifiers) {
        return lookup.select(subtype, qualifiers);
    }

    @Override
    public <U> MicronautInstance<U> select(TypeLiteral<U> subtype, Annotation... qualifiers) {
        return lookup.select(subtype, qualifiers);
    }

    @Override
    public MicronautInstance<Object> select(
        AnnotationValue<?> qualifier,
        AnnotationValue<?>... qualifiers) {
        return lookup.select(qualifier, qualifiers);
    }

    @Override
    public <U> MicronautInstance<U> select(
        Class<U> subtype, AnnotationValue<?> qualifier,
        AnnotationValue<?>... qualifiers) {
        return lookup.select(subtype, qualifier, qualifiers);
    }

    @Override
    public <U> MicronautInstance<U> select(
        Argument<U> subtype, AnnotationValue<?>... qualifiers) {
        return lookup.select(subtype, qualifiers);
    }

    @Override
    public <U> MicronautInstance<U> select(Argument<U> subtype,
                                           Qualifier<U> qualifier) {
        return lookup.select(subtype, qualifier);
    }

    @Override
    public <U> MicronautInstance<U> select(Class<U> subtype,
                                           Qualifier<U> qualifier) {
        return lookup.select(subtype, qualifier);
    }

    @Override
    public boolean isUnsatisfied() {
        return lookup.isUnsatisfied();
    }

    @Override
    public boolean isAmbiguous() {
        return lookup.isAmbiguous();
    }

    @Override
    public void destroy(Object instance) {
        lookup.destroy(instance);
    }

    @Override
    public Handle<Object> getHandle() {
        return lookup.getHandle();
    }

    @Override
    public Iterable<? extends Handle<Object>> handles() {
        return lookup.handles();
    }

    @Override
    public Object get() {
        return lookup.get();
    }

    @Override
    public Iterator<Object> iterator() {
        return lookup.iterator();
    }
}
