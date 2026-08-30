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
package io.micronaut.cdi.se;

import io.micronaut.cdi.runtime.CdiBeanContainer;
import io.micronaut.cdi.runtime.CdiInstance;
import io.micronaut.context.ApplicationContext;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.type.Argument;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.se.SeContainer;
import jakarta.enterprise.inject.spi.BeanContainer;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.enterprise.util.TypeLiteral;

import java.lang.annotation.Annotation;
import java.util.Iterator;

/**
 * One running SE container: the Micronaut application context the initializer built, worn as the
 * {@code SeContainer} of the specification's SE bootstrap.
 *
 * <p>It is a lookup of every bean the container holds — that is what {@code SeContainer} extending
 * {@code Instance<Object>} means — and it closes the context when it is closed. Closing it twice is the caller's
 * error the specification names, and is reported as {@code IllegalStateException}.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
final class MicronautSeContainer implements SeContainer {

    private final ApplicationContext context;
    private final CdiInstance<Object> lookup;
    private volatile boolean running = true;

    MicronautSeContainer(ApplicationContext context) {
        this.context = context;
        this.lookup = new CdiInstance<>(context, Argument.OBJECT_ARGUMENT);
    }

    @Override
    public void close() {
        if (!running) {
            throw new IllegalStateException("The container is not running: it was already shut down, and a "
                + "container is shut down once");
        }
        running = false;
        context.close();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public BeanManager getBeanManager() {
        return container();
    }

    @Override
    public BeanContainer getBeanContainer() {
        return container();
    }

    private CdiBeanContainer container() {
        if (!running) {
            throw new IllegalStateException("The container is not running");
        }
        return context.getBean(CdiBeanContainer.class);
    }

    @Override
    public Instance<Object> select(Annotation... qualifiers) {
        return active().select(qualifiers);
    }

    @Override
    public <U> Instance<U> select(Class<U> subtype, Annotation... qualifiers) {
        return active().select(subtype, qualifiers);
    }

    @Override
    public <U> Instance<U> select(TypeLiteral<U> subtype, Annotation... qualifiers) {
        return active().select(subtype, qualifiers);
    }

    @Override
    public boolean isUnsatisfied() {
        return active().isUnsatisfied();
    }

    @Override
    public boolean isAmbiguous() {
        return active().isAmbiguous();
    }

    @Override
    public void destroy(Object instance) {
        active().destroy(instance);
    }

    @Override
    public Handle<Object> getHandle() {
        return active().getHandle();
    }

    @Override
    public Iterable<? extends Handle<Object>> handles() {
        return active().handles();
    }

    @Override
    public Object get() {
        return active().get();
    }

    @Override
    public Iterator<Object> iterator() {
        return active().iterator();
    }

    private Instance<Object> active() {
        if (!running) {
            throw new IllegalStateException("The container is not running");
        }
        return lookup;
    }
}
