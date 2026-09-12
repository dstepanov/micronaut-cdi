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

import io.micronaut.cdi.runtime.CdiInstance;
import io.micronaut.context.ApplicationContext;
import io.micronaut.core.type.Argument;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.util.TypeLiteral;

import java.lang.annotation.Annotation;
import java.util.Iterator;

/**
 * A programmatic lookup of this implementation, under ArC's name for one.
 *
 * @param <T> The bean type
 */
final class CdiBackedInstance<T> implements InjectableInstance<T> {

    private final ApplicationContext context;
    private final CdiInstance<T> delegate;

    private CdiBackedInstance(ApplicationContext context, CdiInstance<T> delegate) {
        this.context = context;
        this.delegate = delegate;
    }

    static <T> InjectableInstance<T> of(ApplicationContext context, Argument<T> type, Annotation... qualifiers) {
        for (Annotation qualifier : qualifiers) {
            // section 5.2.3: a lookup selected with an annotation that is not a qualifier is a programming error,
            // and the specification has it rejected where it is asked for rather than where it is resolved
            if (!qualifier.annotationType().isAnnotationPresent(jakarta.inject.Qualifier.class)) {
                throw new IllegalArgumentException(qualifier.annotationType().getName() + " is not a qualifier");
            }
        }
        return new CdiBackedInstance<>(context, new CdiInstance<>(context, type, qualifiers));
    }

    @Override
    public InstanceHandle<T> getHandle() {
        return new CdiBackedHandle<>(context, delegate.getHandle());
    }

    @Override
    public Iterable<InstanceHandle<T>> handles() {
        Iterable<? extends Instance.Handle<T>> handles = delegate.handles();
        return () -> {
            Iterator<? extends Instance.Handle<T>> iterator = handles.iterator();
            return new Iterator<>() {
                @Override
                public boolean hasNext() {
                    return iterator.hasNext();
                }

                @Override
                public InstanceHandle<T> next() {
                    return new CdiBackedHandle<>(context, iterator.next());
                }
            };
        };
    }

    @Override
    public InjectableInstance<T> select(Annotation... qualifiers) {
        return new CdiBackedInstance<>(context, (CdiInstance<T>) delegate.select(qualifiers));
    }

    @Override
    public <U extends T> InjectableInstance<U> select(Class<U> subtype, Annotation... qualifiers) {
        return new CdiBackedInstance<>(context, (CdiInstance<U>) delegate.select(subtype, qualifiers));
    }

    @Override
    public <U extends T> Instance<U> select(TypeLiteral<U> subtype, Annotation... qualifiers) {
        return new CdiBackedInstance<>(context, (CdiInstance<U>) delegate.select(subtype, qualifiers));
    }

    @Override
    public T get() {
        return delegate.get();
    }

    @Override
    public boolean isUnsatisfied() {
        return delegate.isUnsatisfied();
    }

    @Override
    public boolean isAmbiguous() {
        return delegate.isAmbiguous();
    }

    @Override
    public void destroy(T instance) {
        delegate.destroy(instance);
    }

    @Override
    public Iterator<T> iterator() {
        return delegate.iterator();
    }
}
