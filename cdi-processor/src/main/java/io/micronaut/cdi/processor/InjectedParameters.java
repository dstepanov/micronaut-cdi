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
package io.micronaut.cdi.processor;

import io.micronaut.core.annotation.Internal;
import io.micronaut.inject.ast.MethodElement;
import io.micronaut.inject.ast.ParameterElement;

/**
 * Reads the parameters of a producer, a disposer or an observer method as the injection points they are.
 *
 * <p>Each of those methods is qualified in its own right — a producer method carries the qualifiers of the bean
 * it produces, an observer method the ones of the event it observes — and the annotation metadata of a parameter
 * carries what its method declares as well as what the parameter itself does. Left alone, a parameter that names
 * no qualifier would be resolved by the qualifier of the method it belongs to, which for a producer means
 * resolving to the very bean it is producing.</p>
 *
 * <p>What the parameter did not declare is therefore taken off it, so that an injection point that names no
 * qualifier is one, and resolves the way section 2.2.8 says it does.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class InjectedParameters {

    private InjectedParameters() {
    }

    /**
     * Takes off every parameter of the method the qualifiers it did not declare itself.
     *
     * @param method The producer, disposer or observer method
     */
    public static void readAsInjectionPoints(MethodElement method) {
        for (ParameterElement parameter : method.getParameters()) {
            for (String qualifier : parameter.getAnnotationMetadata()
                .getAnnotationNamesByStereotype(Cdi.QUALIFIER)) {
                if (!parameter.hasDeclaredAnnotation(qualifier)) {
                    parameter.removeAnnotation(qualifier);
                }
            }
        }
    }
}
