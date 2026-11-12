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
import io.micronaut.core.annotation.AnnotationClassValue;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.AnnotationValueBuilder;
import io.micronaut.core.annotation.Internal;
import jakarta.enterprise.inject.build.compatible.spi.InvokerInfo;
import org.jspecify.annotations.Nullable;

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
 * @param async              The async handler the method matches (CDI 5.0), or {@code null} where it is not
 *                           asynchronous
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
record ElementInvokerInfo(String beanClassName, String methodName, String[] parameterTypeNames,
                          boolean staticMethod, boolean instanceLookup, boolean[] argumentLookups,
                          AsyncHandlerTypes.@Nullable Match async)
    implements InvokerInfo {

    /**
     * The invoker as the annotation value a synthetic component carries it to runtime in.
     *
     * @return The record
     */
    AnnotationValue<CdiRecordedInvoker> toRecord() {
        AnnotationValueBuilder<CdiRecordedInvoker> record = AnnotationValue.builder(CdiRecordedInvoker.class)
            .member("beanClass", beanClassName)
            .member("beanType", new AnnotationClassValue<>(beanClassName))
            .member("method", methodName)
            .member("parameterTypes", parameterTypeNames)
            .member("staticMethod", staticMethod)
            .member("instanceLookup", instanceLookup)
            .member("argumentLookups", argumentLookups);
        if (async != null) {
            record.member("async", true)
                .member("asyncType", async.asyncType())
                .member("asyncParameter", async.parameter());
            String handler = async.handlerClass();
            if (handler != null) {
                record.member("asyncHandler", handler);
            }
        }
        return record.build();
    }

    @Override
    public String toString() {
        return "Invoker[" + beanClassName + "#" + methodName + "]";
    }
}
