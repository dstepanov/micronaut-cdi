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
package io.quarkus.arc;

import io.micronaut.cdi.runtime.CdiBean;
import jakarta.enterprise.context.spi.CreationalContext;
import jakarta.enterprise.inject.spi.InjectionPoint;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.Set;

/**
 * A bean of this implementation, answering ArC's interface as well as the specification's.
 *
 * @param <T> The bean type
 */
final class CdiBackedBean<T> implements InjectableBean<T> {

    private final CdiBean<T> delegate;

    CdiBackedBean(CdiBean<T> delegate) {
        this.delegate = delegate;
    }

    CdiBean<T> delegate() {
        return delegate;
    }

    @Override
    public String getIdentifier() {
        // the name of the definition the compiler generated for the bean, which is one class per bean and so is
        // as good an identifier as the name of the class ArC generates
        return delegate.definition().getClass().getName();
    }

    @Override
    public boolean hasPriority() {
        return delegate.definition().intValue(jakarta.annotation.Priority.class).isPresent();
    }

    @Override
    public int getPriority() {
        return delegate.definition().intValue(jakarta.annotation.Priority.class).orElse(0);
    }

    @Override
    public @Nullable InjectableBean<?> getDeclaringBean() {
        return null;
    }

    @Override
    public Class<?> getBeanClass() {
        return delegate.getBeanClass();
    }

    @Override
    public Set<InjectionPoint> getInjectionPoints() {
        return delegate.getInjectionPoints();
    }

    @Override
    public Set<Type> getTypes() {
        return delegate.getTypes();
    }

    @Override
    public Set<Annotation> getQualifiers() {
        return delegate.getQualifiers();
    }

    @Override
    public Class<? extends Annotation> getScope() {
        return delegate.getScope();
    }

    @Override
    public @Nullable String getName() {
        return delegate.getName();
    }

    @Override
    public Set<Class<? extends Annotation>> getStereotypes() {
        return delegate.getStereotypes();
    }

    @Override
    public boolean isAlternative() {
        return delegate.isAlternative();
    }

    @Override
    public T create(CreationalContext<T> creationalContext) {
        return delegate.create(creationalContext);
    }

    @Override
    public void destroy(T instance, CreationalContext<T> creationalContext) {
        delegate.destroy(instance, creationalContext);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof CdiBackedBean<?> other && delegate.equals(other.delegate);
    }

    @Override
    public int hashCode() {
        return delegate.hashCode();
    }

    @Override
    public String toString() {
        return delegate.toString();
    }
}
