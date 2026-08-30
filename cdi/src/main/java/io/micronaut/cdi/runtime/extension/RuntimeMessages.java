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
import jakarta.enterprise.inject.build.compatible.spi.BeanInfo;
import jakarta.enterprise.inject.build.compatible.spi.Messages;
import jakarta.enterprise.inject.build.compatible.spi.ObserverInfo;
import jakarta.enterprise.lang.model.AnnotationTarget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * What an extension has to say while the container is starting.
 *
 * <p>There is no compiler to report through at this point, so what is said is logged, and what is reported as an
 * error stops the container from starting: the specification has a deployment with an error in it not deploy,
 * and the container starting is the nearest thing to a deployment here.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class RuntimeMessages implements Messages {

    private static final Logger LOG = LoggerFactory.getLogger(RuntimeMessages.class);

    private final List<String> errors = new ArrayList<>();

    @Override
    public void info(String message) {
        LOG.info(message);
    }

    @Override
    public void info(String message, AnnotationTarget relatedTo) {
        LOG.info("{} ({})", message, relatedTo);
    }

    @Override
    public void info(String message, BeanInfo relatedTo) {
        LOG.info("{} ({})", message, relatedTo);
    }

    @Override
    public void info(String message, ObserverInfo relatedTo) {
        LOG.info("{} ({})", message, relatedTo);
    }

    @Override
    public void warn(String message) {
        LOG.warn(message);
    }

    @Override
    public void warn(String message, AnnotationTarget relatedTo) {
        LOG.warn("{} ({})", message, relatedTo);
    }

    @Override
    public void warn(String message, BeanInfo relatedTo) {
        LOG.warn("{} ({})", message, relatedTo);
    }

    @Override
    public void warn(String message, ObserverInfo relatedTo) {
        LOG.warn("{} ({})", message, relatedTo);
    }

    @Override
    public void error(String message) {
        errors.add(message);
    }

    @Override
    public void error(String message, AnnotationTarget relatedTo) {
        errors.add(message + " (" + relatedTo + ")");
    }

    @Override
    public void error(String message, BeanInfo relatedTo) {
        errors.add(message + " (" + relatedTo + ")");
    }

    @Override
    public void error(String message, ObserverInfo relatedTo) {
        errors.add(message + " (" + relatedTo + ")");
    }

    @Override
    public void error(Exception exception) {
        errors.add(exception.toString());
    }

    /**
     * The errors the extensions reported, which are what stops the container from starting.
     *
     * @return The errors, in the order they were reported
     */
    List<String> errors() {
        return errors;
    }
}
