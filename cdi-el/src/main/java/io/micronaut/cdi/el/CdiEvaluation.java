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
package io.micronaut.cdi.el;

import jakarta.enterprise.context.spi.CreationalContext;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanContainer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One evaluation of an expression, and the dependent beans it reached.
 *
 * <p>A bean of the dependent pseudo-scope that a name of an expression resolves to belongs to the evaluation: it
 * is created at most once however often the expression names it, and it is destroyed when the evaluation
 * completes. The wrapped expressions of {@link CdiExpressionFactory} open an evaluation around each call and
 * register it on the context they evaluate against, which is where {@link CdiELResolver} finds it.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
final class CdiEvaluation {

    private final Map<Bean<?>, Object> instances = new HashMap<>();
    private final List<CreationalContext<?>> created = new ArrayList<>();

    /**
     * The instance of a dependent bean this evaluation uses, created the first time it is asked for.
     *
     * @param bean The dependent bean
     * @param beans The container that creates it
     * @return The one instance of this evaluation
     */
    Object instanceOf(Bean<?> bean, BeanContainer beans) {
        Object existing = instances.get(bean);
        if (existing != null) {
            return existing;
        }
        CreationalContext<?> creationalContext = beans.createCreationalContext(bean);
        // registered before the reference is asked for, so that what a failed creation left behind is released
        created.add(creationalContext);
        Object instance = beans.getReference(bean, Object.class, creationalContext);
        instances.put(bean, instance);
        return instance;
    }

    /**
     * Destroys what the evaluation created, every instance even when the destruction of one fails.
     */
    void complete() {
        RuntimeException failure = null;
        for (CreationalContext<?> creationalContext : created) {
            try {
                creationalContext.release();
            } catch (RuntimeException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        created.clear();
        instances.clear();
        if (failure != null) {
            throw failure;
        }
    }
}
