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
package io.micronaut.cdi.context;

import io.micronaut.cdi.annotation.CdiApplicationScope;
import io.micronaut.context.scope.AbstractConcurrentCustomScope;
import io.micronaut.context.scope.CreatedBean;
import io.micronaut.core.annotation.Internal;
import io.micronaut.inject.BeanIdentifier;
import jakarta.inject.Singleton;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The context of the application scope, which holds one instance of every application scoped bean for as long as
 * the application runs.
 *
 * <p>The context is active from the moment the container starts until it shuts down, and the instances in it are
 * destroyed as it shuts down, which is what the specification's application shutdown lifecycle asks for.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Singleton
@Internal
public final class ApplicationScope extends AbstractConcurrentCustomScope<CdiApplicationScope> {

    private final Map<BeanIdentifier, CreatedBean<?>> instances = new ConcurrentHashMap<>(32);

    private final RequestScope requestScope;

    public ApplicationScope(RequestScope requestScope) {
        super(CdiApplicationScope.class);
        this.requestScope = requestScope;
    }

    @Override
    protected <T> io.micronaut.context.scope.CreatedBean<T> doCreate(
        io.micronaut.context.scope.BeanCreationContext<T> creationContext) {
        // section 2.5.6: the request context is active during the @PostConstruct callback of any bean, and an
        // application scoped bean is created lazily, wherever its proxy was first reached through
        return requestScope.duringCreation(() -> super.doCreate(creationContext));
    }

    @Override
    protected Map<BeanIdentifier, CreatedBean<?>> getScopeMap(boolean forCreation) {
        return instances;
    }

    /**
     * Whether the scope already holds an instance of the given type, which is what a conditional observer of
     * section 2.8.2 asks.
     *
     * @param beanType The type of the bean
     * @return Whether an instance is held
     */
    public boolean holdsInstanceOf(Class<?> beanType) {
        for (io.micronaut.context.scope.CreatedBean<?> created : java.util.List.copyOf(instances.values())) {
            if (io.micronaut.cdi.runtime.CdiContext.CONTEXTUAL_STORE_ID.equals(created.id())) {
                continue;
            }
            if (beanType.isAssignableFrom(created.definition().getBeanType())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Destroys the instance of the given bean type this scope holds, if it holds one.
     *
     * <p>It is what the alterable context of the specification does: the instance is destroyed and the scope
     * forgets it, so that the next reference through the client proxy is a fresh instance.</p>
     *
     * @param beanType The type of the bean
     * @return Whether an instance was destroyed
     */
    /**
     * Destroys the instance of the given bean this scope holds, if it holds one: matched by the definition
     * rather than by a class, because a produced bean's class — the producer's declaring class — says nothing
     * about the produced instance, and one class may be the type of several beans.
     *
     * @param definition The bean's definition, possibly the proxy's
     * @return Whether an instance was destroyed
     */
    public boolean destroyInstanceOf(io.micronaut.inject.BeanDefinition<?> definition) {
        Map<BeanIdentifier, CreatedBean<?>> beans = getScopeMap(false);
        if (beans == null) {
            return false;
        }
        String targetType = definition instanceof io.micronaut.inject.ProxyBeanDefinition<?> proxy
            ? proxy.getTargetDefinitionType().getName() : null;
        for (CreatedBean<?> created : java.util.List.copyOf(beans.values())) {
            if (io.micronaut.cdi.runtime.CdiContext.CONTEXTUAL_STORE_ID.equals(created.id())) {
                continue;
            }
            io.micronaut.inject.BeanDefinition<?> held = created.definition();
            if (held.equals(definition)
                || targetType != null && held.getClass().getName().equals(targetType)) {
                // taken out of the map first so nobody is served a destroyed instance, then closed outside
                // the scope's lock: a @PreDestroy must neither run under it nor have its failure swallowed
                if (beans.remove(created.id(), created)) {
                    created.close();
                }
                return true;
            }
        }
        return false;
    }

    public boolean destroyInstanceOf(Class<?> beanType) {
        for (io.micronaut.context.scope.CreatedBean<?> created : java.util.List.copyOf(instances.values())) {
            if (io.micronaut.cdi.runtime.CdiContext.CONTEXTUAL_STORE_ID.equals(created.id())) {
                // the store a context keeps its handed-in contextuals in is not a bean of the scope
                continue;
            }
            if (beanType.isAssignableFrom(created.definition().getBeanType())) {
                // taken out of the map first so nobody is served a destroyed instance, then closed outside
                // the scope's lock: a @PreDestroy must neither run under it nor have its failure swallowed
                if (instances.remove(created.id(), created)) {
                    created.close();
                }
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean isRunning() {
        return true;
    }

    @Override
    public void close() {
        instances.clear();
    }
}
