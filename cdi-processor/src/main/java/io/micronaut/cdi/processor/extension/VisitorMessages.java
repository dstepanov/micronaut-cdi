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
import io.micronaut.inject.ast.Element;
import io.micronaut.inject.visitor.VisitorContext;
import jakarta.enterprise.inject.build.compatible.spi.BeanInfo;
import jakarta.enterprise.inject.build.compatible.spi.Messages;
import jakarta.enterprise.inject.build.compatible.spi.ObserverInfo;
import jakarta.enterprise.lang.model.AnnotationTarget;

/**
 * What an extension has to say, said through the compiler.
 *
 * <p>An extension runs while the classes it enhances are being compiled, so the natural place for it to report
 * something is the compiler that is running it: an error an extension reports fails the compilation, and a
 * warning appears beside the declaration it is about.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class VisitorMessages implements Messages {

    private final VisitorContext context;

    VisitorMessages(VisitorContext context) {
        this.context = context;
    }

    @Override
    public void info(String message) {
        context.info(message, null);
    }

    @Override
    public void info(String message, AnnotationTarget relatedTo) {
        context.info(message, elementOf(relatedTo));
    }

    @Override
    public void info(String message, BeanInfo relatedTo) {
        context.info(message, null);
    }

    @Override
    public void info(String message, ObserverInfo relatedTo) {
        context.info(message, null);
    }

    @Override
    public void warn(String message) {
        context.warn(message, null);
    }

    @Override
    public void warn(String message, AnnotationTarget relatedTo) {
        context.warn(message, elementOf(relatedTo));
    }

    @Override
    public void warn(String message, BeanInfo relatedTo) {
        context.warn(message, null);
    }

    @Override
    public void warn(String message, ObserverInfo relatedTo) {
        context.warn(message, null);
    }

    @Override
    public void error(String message) {
        context.fail(message, null);
    }

    @Override
    public void error(String message, AnnotationTarget relatedTo) {
        context.fail(message, elementOf(relatedTo));
    }

    @Override
    public void error(String message, BeanInfo relatedTo) {
        context.fail(message, null);
    }

    @Override
    public void error(String message, ObserverInfo relatedTo) {
        context.fail(message, null);
    }

    @Override
    public void error(Exception exception) {
        context.fail(exception.toString(), null);
    }

    /**
     * The declaration a message is about, where it is one this module handed the extension.
     */
    private static @org.jspecify.annotations.Nullable Element elementOf(AnnotationTarget relatedTo) {
        return relatedTo instanceof ElementDeclarationInfo declaration ? declaration.element() : null;
    }
}
