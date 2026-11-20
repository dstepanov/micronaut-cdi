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

import io.micronaut.context.BeanContext;
import io.micronaut.context.WatchableBeanContext;
import io.micronaut.context.watch.BeanDefinitionChange;
import io.micronaut.core.annotation.Internal;
import jakarta.inject.Singleton;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * What the invokers of the container invoke - the bean and its executable method - found once for every invoker of
 * the same method rather than on every invocation, and found again once the definitions change. One cache of the
 * container, keyed by the method an invoker names, with one watch of the definitions: invokers are built again and
 * again, by the deployment validation and by whoever builds one on demand, and none of them holds anything of its own.
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Singleton
@Internal
public final class InvokerTargets {

    private final Map<Key, RecordedInvoker.Target> targets = new ConcurrentHashMap<>();

    public InvokerTargets(BeanContext beanContext) {
        if (beanContext instanceof WatchableBeanContext watchable) {
            // a definition registered or replaced at runtime may be the bean to invoke from then on. The watch
            // belongs to this bean, and is closed as it is destroyed
            watchable.definitions().watch(this::definitionsChanged);
        }
    }

    private void definitionsChanged(BeanDefinitionChange<Object> change) {
        if (!change.initial()) {
            targets.clear();
        }
    }

    /**
     * The target of the invokers of a method, found the first time it is asked for.
     *
     * @param key  The method
     * @param find Finds it
     * @return The target
     */
    RecordedInvoker.Target targetOf(Key key, Supplier<RecordedInvoker.Target> find) {
        RecordedInvoker.Target found = targets.get(key);
        if (found == null) {
            // found outside the map's lock: finding it may create nothing, but asks the bean context
            found = find.get();
            RecordedInvoker.Target raced = targets.putIfAbsent(key, found);
            if (raced != null) {
                found = raced;
            }
        }
        return found;
    }

    /**
     * How many methods the cache holds the targets of.
     *
     * @return The number
     */
    int size() {
        return targets.size();
    }

    /**
     * The method an invoker names.
     *
     * @param beanClassName  The bean class
     * @param methodName     The method
     * @param parameterTypes The names of its parameter types
     */
    record Key(String beanClassName, String methodName, List<String> parameterTypes) {
    }
}
