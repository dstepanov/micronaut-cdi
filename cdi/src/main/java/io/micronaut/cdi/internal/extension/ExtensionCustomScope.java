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
package io.micronaut.cdi.internal.extension;

import io.micronaut.cdi.internal.runtime.CdiBean;
import io.micronaut.cdi.internal.runtime.CdiBeanContainer;
import io.micronaut.cdi.internal.runtime.CdiCreationalContext;
import io.micronaut.context.BeanContext;
import io.micronaut.context.scope.BeanCreationContext;
import io.micronaut.context.scope.CreatedBean;
import io.micronaut.context.scope.CustomScope;
import io.micronaut.core.annotation.Internal;
import io.micronaut.inject.BeanIdentifier;
import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.enterprise.context.spi.AlterableContext;
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
        @SuppressWarnings("unchecked")
        CdiBean<T> bean = (CdiBean<T>) container.canonicalBean(creationContext.definition());
        @SuppressWarnings("unchecked")
        CreationalContext<T> creationalContext = (CreationalContext<T>) container.createCreationalContext(bean);
        T held = context.get(new CreatingContextual<>(beanContext, bean, creationContext), creationalContext);
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
        AlterableContext active = null;
        for (AlterableContext context : contexts) {
            if (context.isActive()) {
                if (active != null) {
                    throw new IllegalStateException("More than one context of " + scopeAnnotation.getName()
                        + " is active on the current thread");
                }
                active = context;
            }
        }
        if (active != null) {
            return active;
        }
        throw new ContextNotActiveException("No context of " + scopeAnnotation.getName()
            + " is active on the current thread");
    }

    /**
     * The contextual handed to the extension's context: the bean itself, equal to it both ways, so that the
     * context finds what it holds whichever of the two it is asked with — but creating goes to the container's
     * own creation, so that the bean's create does not come back through this scope. What that creation made,
     * dependents included, is tracked by the creational context, and destroying the instance through either
     * the bean or this contextual releases it.
     *
     * @param <T> The bean type
     */
    private static final class CreatingContextual<T> extends CdiBean<T> {

        private final BeanCreationContext<T> creation;

        private CreatingContextual(BeanContext beanContext, CdiBean<T> bean, BeanCreationContext<T> creation) {
            super(beanContext, bean.definition());
            this.creation = creation;
        }

        @Override
        public T create(CreationalContext<T> creationalContext) {
            CreatedBean<T> created = creation.create();
            if (creationalContext instanceof CdiCreationalContext<T> tracking) {
                tracking.track(created);
            }
            return created.bean();
        }
    }
}
