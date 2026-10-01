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

import io.micronaut.cdi.internal.metadata.CdiRecordedInvoker;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import jakarta.enterprise.inject.build.compatible.spi.InvokerInfo;

/**
 * An invoker an extension built in the registration phase (CDI 4.1, chapter 7), as the compilation holds it: what
 * names the method to invoke - the bean class, the method, its parameter types - and what the builder asked to be
 * looked up. It goes to runtime as the {@link CdiRecordedInvoker} record a synthetic component carries, which the
 * container reads into the invoker that invokes the method.
 *
 * @param beanClassName      The bean class
 * @param methodName         The method
 * @param parameterTypeNames The parameter types, an array type spelled with its brackets
 * @param staticMethod       Whether the method is static
 * @param instanceLookup     Whether the instance is looked up
 * @param argumentLookups    Whether each argument is looked up
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
record ElementInvokerInfo(String beanClassName, String methodName, String[] parameterTypeNames,
                          boolean staticMethod, boolean instanceLookup, boolean[] argumentLookups)
    implements InvokerInfo {

    /**
     * The invoker as the annotation value a synthetic component carries it to runtime in.
     *
     * @return The record
     */
    AnnotationValue<CdiRecordedInvoker> toRecord() {
        return AnnotationValue.builder(CdiRecordedInvoker.class)
            .member("beanClass", beanClassName)
            .member("method", methodName)
            .member("parameterTypes", parameterTypeNames)
            .member("staticMethod", staticMethod)
            .member("instanceLookup", instanceLookup)
            .member("argumentLookups", argumentLookups)
            .build();
    }

    @Override
    public String toString() {
        return "Invoker[" + beanClassName + "#" + methodName + "]";
    }
}
