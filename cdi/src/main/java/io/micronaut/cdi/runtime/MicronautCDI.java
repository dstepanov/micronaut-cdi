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
package io.micronaut.cdi.runtime;

import io.micronaut.core.annotation.Internal;
import io.micronaut.core.type.Argument;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.BeanContainer;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.enterprise.util.TypeLiteral;

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
public final class MicronautCDI extends CDI<Object> {

    private final CdiBeanContainer container;
    private final Instance<Object> lookup;

    MicronautCDI(CdiBeanContainer container) {
        this.container = container;
        this.lookup = new CdiInstance<>(container.beanContext(), Argument.OBJECT_ARGUMENT);
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
    public Instance<Object> select(Annotation... qualifiers) {
        return lookup.select(qualifiers);
    }

    @Override
    public <U> Instance<U> select(Class<U> subtype, Annotation... qualifiers) {
        return lookup.select(subtype, qualifiers);
    }

    @Override
    public <U> Instance<U> select(TypeLiteral<U> subtype, Annotation... qualifiers) {
        return lookup.select(subtype, qualifiers);
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
