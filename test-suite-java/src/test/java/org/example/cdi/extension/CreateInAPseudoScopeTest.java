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
package org.example.cdi.extension;

import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.context.spi.AlterableContext;
import jakarta.enterprise.context.spi.Contextual;
import jakarta.enterprise.context.spi.CreationalContext;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.se.SeContainer;
import jakarta.enterprise.inject.se.SeContainerInitializer;
import jakarta.enterprise.inject.spi.AfterBeanDiscovery;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.enterprise.inject.spi.Extension;
import jakarta.inject.Scope;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * {@code Bean.create} creates a new contextual instance every time it is called (section 6.1), for a bean of a
 * pseudo-scope of the application's own as well: the instance the scope's context holds is the context's business,
 * and is not what {@code create} hands back.
 */
class CreateInAPseudoScopeTest {

    @Scope
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
    public @interface TaskScoped {
    }

    public static class Task {
    }

    @Dependent
    public static class TaskFactory {
        @Produces
        @TaskScoped
        Task task() {
            return new Task();
        }
    }

    static class TaskContext implements AlterableContext {
        final Map<Contextual<?>, Object> held = new ConcurrentHashMap<>();

        @Override
        public Class<? extends Annotation> getScope() {
            return TaskScoped.class;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T get(Contextual<T> contextual, CreationalContext<T> creationalContext) {
            return (T) held.computeIfAbsent(contextual, key -> contextual.create(creationalContext));
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T get(Contextual<T> contextual) {
            return (T) held.get(contextual);
        }

        @Override
        public boolean isActive() {
            return true;
        }

        @Override
        public void destroy(Contextual<?> contextual) {
            held.remove(contextual);
        }
    }

    public static class Adding implements Extension {
        void add(@Observes AfterBeanDiscovery event) {
            event.addContext(new TaskContext());
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void createMakesANewInstanceOfABeanOfAPseudoScope() {
        try (SeContainer container = SeContainerInitializer.newInstance().disableDiscovery()
            .addBeanClasses(TaskFactory.class).addExtensions(new Adding()).initialize()) {
            BeanManager manager = container.getBeanManager();
            Bean<Task> bean = (Bean<Task>) manager.resolve(manager.getBeans(Task.class));
            Task held = container.select(Task.class).get();
            assertSame(held, container.select(Task.class).get(), "the context holds one instance");
            CreationalContext<Task> creational = manager.createCreationalContext(bean);
            Task first = bean.create(creational);
            Task second = bean.create(creational);
            assertNotSame(first, second);
            assertNotSame(held, first);
        }
    }
}
