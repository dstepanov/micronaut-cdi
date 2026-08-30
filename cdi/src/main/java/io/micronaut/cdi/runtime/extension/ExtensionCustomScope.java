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
package io.micronaut.cdi.runtime.extension;

import io.micronaut.cdi.runtime.CdiBeanContainer;
import io.micronaut.context.BeanContext;
import io.micronaut.context.scope.BeanCreationContext;
import io.micronaut.context.scope.CreatedBean;
import io.micronaut.context.scope.CustomScope;
import io.micronaut.core.annotation.Internal;
import io.micronaut.inject.BeanIdentifier;
import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.enterprise.context.spi.AlterableContext;
import jakarta.enterprise.context.spi.Contextual;
import jakarta.enterprise.context.spi.CreationalContext;

import java.lang.annotation.Annotation;
import java.util.List;
import java.util.Optional;

/**
 * The Micronaut custom scope that stands in front of a context a build compatible extension registered: a bean
 * of the scope resolves through the context the extension provided, which is what section 2.10.1 registers the
 * context for.
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
final class ExtensionCustomScope implements CustomScope<Annotation> {

    private final Class<? extends Annotation> scopeAnnotation;
    private final List<AlterableContext> contexts;
    private final BeanContext beanContext;

    ExtensionCustomScope(Class<? extends Annotation> scopeAnnotation, List<AlterableContext> contexts,
                         BeanContext beanContext) {
        this.scopeAnnotation = scopeAnnotation;
        this.contexts = contexts;
        this.beanContext = beanContext;
    }

    @SuppressWarnings("unchecked")
    @Override
    public Class<Annotation> annotationType() {
        return (Class<Annotation>) scopeAnnotation;
    }

    @Override
    public <T> T getOrCreate(BeanCreationContext<T> creationContext) {
        AlterableContext context = activeContext();
        CdiBeanContainer container = beanContext.getBean(CdiBeanContainer.class);
        jakarta.enterprise.inject.spi.Bean<T> bean =
            (jakarta.enterprise.inject.spi.Bean<T>) container.canonicalBean(creationContext.definition());
        T held = context.get(new CreatingContextual<>(bean, creationContext),
            (CreationalContext<T>) container.createCreationalContext(bean));
        if (held == null) {
            throw new ContextNotActiveException("The context of " + scopeAnnotation.getName()
                + " holds no instance and created none");
        }
        return held;
    }

    @Override
    public <T> Optional<T> remove(BeanIdentifier identifier) {
        return Optional.empty();
    }

    private AlterableContext activeContext() {
        for (AlterableContext context : contexts) {
            if (context.isActive()) {
                return context;
            }
        }
        throw new ContextNotActiveException("No context of " + scopeAnnotation.getName()
            + " is active on the current thread");
    }

    /**
     * The contextual handed to the extension's context: it is the bean, for identity — the context keys what
     * it holds by it — but creating goes to the container's own creation, so that the bean's create does not
     * come back through this scope.
     *
     * @param <T> The bean type
     */
    private static final class CreatingContextual<T> implements Contextual<T> {

        private final jakarta.enterprise.inject.spi.Bean<T> bean;
        private final BeanCreationContext<T> creation;

        private CreatingContextual(jakarta.enterprise.inject.spi.Bean<T> bean, BeanCreationContext<T> creation) {
            this.bean = bean;
            this.creation = creation;
        }

        @Override
        public T create(CreationalContext<T> creationalContext) {
            CreatedBean<T> created = creation.create();
            return created.bean();
        }

        @Override
        public void destroy(T instance, CreationalContext<T> creationalContext) {
            bean.destroy(instance, creationalContext);
        }

        @Override
        public boolean equals(Object o) {
            if (o instanceof CreatingContextual<?> other) {
                return bean.equals(other.bean);
            }
            return bean.equals(o);
        }

        @Override
        public int hashCode() {
            return bean.hashCode();
        }
    }
}
