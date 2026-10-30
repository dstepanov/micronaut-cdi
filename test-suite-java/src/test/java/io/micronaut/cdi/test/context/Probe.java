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
package io.micronaut.cdi.test.context;

import jakarta.enterprise.context.spi.Contextual;
import jakarta.enterprise.context.spi.CreationalContext;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A contextual a program hands a context: it counts what it created, records what was destroyed by name, and runs
 * the given actions as it creates and as it destroys.
 */
final class Probe implements Contextual<Object> {

    final String name;
    final AtomicInteger created = new AtomicInteger();
    final List<Object> destroyed = new CopyOnWriteArrayList<>();
    private final List<String> log;
    private final Runnable onCreate;
    private final Runnable onDestroy;

    Probe(String name, List<String> log, Runnable onCreate, Runnable onDestroy) {
        this.name = name;
        this.log = log;
        this.onCreate = onCreate;
        this.onDestroy = onDestroy;
    }

    static Probe of(String name, List<String> log) {
        return new Probe(name, log, () -> { }, () -> { });
    }

    static Probe destroyedWith(String name, List<String> log, Runnable onDestroy) {
        return new Probe(name, log, () -> { }, onDestroy);
    }

    @Override
    public Object create(CreationalContext<Object> creationalContext) {
        onCreate.run();
        created.incrementAndGet();
        return new Object();
    }

    @Override
    public void destroy(Object instance, CreationalContext<Object> creationalContext) {
        destroyed.add(instance);
        log.add(name);
        onDestroy.run();
    }
}
