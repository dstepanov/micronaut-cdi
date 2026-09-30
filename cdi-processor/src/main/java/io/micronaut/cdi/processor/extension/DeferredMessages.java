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
package io.micronaut.cdi.processor.extension;

import io.micronaut.core.annotation.Internal;
import io.micronaut.inject.visitor.VisitorContext;
import jakarta.enterprise.inject.build.compatible.spi.BeanInfo;
import jakarta.enterprise.inject.build.compatible.spi.Messages;
import jakarta.enterprise.inject.build.compatible.spi.ObserverInfo;
import jakarta.enterprise.lang.model.AnnotationTarget;

import java.util.ArrayList;
import java.util.List;

/**
 * The messages of the discovery phase, which runs before the compiler hands this module a context to report
 * through: each is kept until one is there, and reported through it then.
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
final class DeferredMessages implements Messages {

    private final List<java.util.function.Consumer<Messages>> pending = new ArrayList<>();

    /**
     * Reports every message kept so far through the context.
     *
     * @param context The context of the compilation
     */
    void reportTo(VisitorContext context) {
        if (pending.isEmpty()) {
            return;
        }
        Messages messages = new VisitorMessages(context);
        for (java.util.function.Consumer<Messages> message : pending) {
            message.accept(messages);
        }
        pending.clear();
    }

    @Override
    public void info(String message) {
        pending.add(m -> m.info(message));
    }

    @Override
    public void info(String message, AnnotationTarget relatedTo) {
        pending.add(m -> m.info(message, relatedTo));
    }

    @Override
    public void info(String message, BeanInfo relatedTo) {
        pending.add(m -> m.info(message, relatedTo));
    }

    @Override
    public void info(String message, ObserverInfo relatedTo) {
        pending.add(m -> m.info(message, relatedTo));
    }

    @Override
    public void warn(String message) {
        pending.add(m -> m.warn(message));
    }

    @Override
    public void warn(String message, AnnotationTarget relatedTo) {
        pending.add(m -> m.warn(message, relatedTo));
    }

    @Override
    public void warn(String message, BeanInfo relatedTo) {
        pending.add(m -> m.warn(message, relatedTo));
    }

    @Override
    public void warn(String message, ObserverInfo relatedTo) {
        pending.add(m -> m.warn(message, relatedTo));
    }

    @Override
    public void error(String message) {
        pending.add(m -> m.error(message));
    }

    @Override
    public void error(String message, AnnotationTarget relatedTo) {
        pending.add(m -> m.error(message, relatedTo));
    }

    @Override
    public void error(String message, BeanInfo relatedTo) {
        pending.add(m -> m.error(message, relatedTo));
    }

    @Override
    public void error(String message, ObserverInfo relatedTo) {
        pending.add(m -> m.error(message, relatedTo));
    }

    @Override
    public void error(Exception exception) {
        pending.add(m -> m.error(exception));
    }
}
