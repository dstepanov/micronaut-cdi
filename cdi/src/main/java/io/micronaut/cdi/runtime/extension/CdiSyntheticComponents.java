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

import io.micronaut.core.annotation.Internal;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticBeanBuilder;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticComponents;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticObserverBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * What an extension adds to the container in the synthesis phase.
 *
 * <p>It gathers what the extension describes rather than acting on it as it is described: an extension may
 * describe several beans, and none of them exists until it has finished.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class CdiSyntheticComponents implements SyntheticComponents {

    private final List<CdiSyntheticBeanBuilder<?>> beans = new ArrayList<>();
    private final List<CdiSyntheticObserverBuilder<?>> observers = new ArrayList<>();
    private final ClassLoader classLoader;

    CdiSyntheticComponents(ClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    @Override
    public <T> SyntheticBeanBuilder<T> addBean(Class<T> implementationClass) {
        CdiSyntheticBeanBuilder<T> builder = new CdiSyntheticBeanBuilder<>(implementationClass, classLoader);
        beans.add(builder);
        return builder;
    }

    @Override
    public <T> SyntheticObserverBuilder<T> addObserver(Class<T> eventType) {
        CdiSyntheticObserverBuilder<T> builder = new CdiSyntheticObserverBuilder<>(eventType, classLoader);
        observers.add(builder);
        return builder;
    }

    @Override
    public <T> SyntheticObserverBuilder<T> addObserver(jakarta.enterprise.lang.model.types.Type eventType) {
        CdiSyntheticObserverBuilder<T> builder = new CdiSyntheticObserverBuilder<>(
            LangModelTypes.reflectiveOf(eventType, classLoader), classLoader);
        observers.add(builder);
        return builder;
    }

    /**
     * The observers the extension described.
     *
     * @return The observers
     */
    List<SyntheticObserverDescription<?>> describedObservers() {
        List<SyntheticObserverDescription<?>> described = new ArrayList<>(observers.size());
        for (CdiSyntheticObserverBuilder<?> builder : observers) {
            described.add(builder.describe());
        }
        return described;
    }

    /**
     * The beans the extension described.
     *
     * @return The beans
     */
    List<SyntheticBean<?>> described() {
        List<SyntheticBean<?>> described = new ArrayList<>(beans.size());
        for (CdiSyntheticBeanBuilder<?> builder : beans) {
            described.add(builder.describe());
        }
        return described;
    }
}
