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

import io.micronaut.cdi.internal.metadata.CdiApplicationScope;
import io.micronaut.cdi.internal.metadata.CdiRequestScope;
import io.micronaut.cdi.internal.metadata.CdiScope;
import io.micronaut.context.annotation.Prototype;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;

import java.util.List;

/**
 * How a scope of the specification is read into Micronaut's: the one translation the mappers, the visitors and
 * the discovery phase of the build compatible extensions write.
 *
 * <p>The scope a bean was written with is recorded as {@link CdiScope}, whether it is normal beside it, and it is
 * what the container reports. The application and the request scopes are served by this module's own Micronaut
 * scopes, and the dependent pseudo-scope is Micronaut's prototype; any other scope - one an application or an
 * extension declares - is served by the context registered for it.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class CdiScopes {

    private CdiScopes() {
    }

    /**
     * The record of the scope a bean was written with.
     *
     * @param scope  The name of the scope annotation
     * @param normal Whether it is a normal scope
     * @return The record
     */
    public static AnnotationValue<CdiScope> recordOf(String scope, boolean normal) {
        if (normal) {
            return AnnotationValue.builder(CdiScope.class).value(scope).member("normal", true).build();
        }
        return AnnotationValue.builder(CdiScope.class).value(scope).build();
    }

    /**
     * What a bean of the given scope is annotated with to be held in it: the Micronaut scope that serves it, where
     * this module has one, and the record of the scope.
     *
     * @param scope  The name of the scope annotation
     * @param normal Whether it is a normal scope, which a scope of the specification's own says itself
     * @return The annotations
     */
    public static List<AnnotationValue<?>> annotationsOf(String scope, boolean normal) {
        return switch (scope) {
            case Cdi.APPLICATION_SCOPED -> List.of(AnnotationValue.builder(CdiApplicationScope.class).build(),
                recordOf(scope, true));
            case Cdi.REQUEST_SCOPED -> List.of(AnnotationValue.builder(CdiRequestScope.class).build(),
                recordOf(scope, true));
            case Cdi.DEPENDENT -> List.of(AnnotationValue.builder(Prototype.class).build(), recordOf(scope, false));
            default -> List.of(recordOf(scope, normal));
        };
    }
}
