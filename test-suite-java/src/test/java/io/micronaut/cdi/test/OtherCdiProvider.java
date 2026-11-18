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
package io.micronaut.cdi.test;

import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.enterprise.inject.spi.CDIProvider;
import jakarta.enterprise.util.TypeLiteral;

import java.lang.annotation.Annotation;
import java.util.Iterator;

/**
 * The provider of another implementation of the specification, registered through the service loader of the tests:
 * it answers only while a test has it answer, as a container of that implementation would only while it runs.
 */
public final class OtherCdiProvider implements CDIProvider {

    /**
     * What the provider answers with while it answers.
     */
    public static final CDI<Object> CURRENT = new OtherCdi();

    static volatile boolean answering;

    @Override
    public CDI<Object> getCDI() {
        if (!answering) {
            throw new IllegalStateException("The other implementation runs no container");
        }
        return CURRENT;
    }

    /**
     * The entry point of the other implementation, which nothing but its identity is asked of.
     */
    private static final class OtherCdi extends CDI<Object> {

        @Override
        public BeanManager getBeanManager() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Instance<Object> select(Annotation... qualifiers) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <U> Instance<U> select(Class<U> subtype, Annotation... qualifiers) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <U> Instance<U> select(TypeLiteral<U> subtype, Annotation... qualifiers) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isUnsatisfied() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isAmbiguous() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void destroy(Object instance) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Handle<Object> getHandle() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Iterable<? extends Handle<Object>> handles() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Object get() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Iterator<Object> iterator() {
            throw new UnsupportedOperationException();
        }
    }
}
