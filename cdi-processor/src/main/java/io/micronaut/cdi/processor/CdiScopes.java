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
import io.micronaut.core.annotation.AnnotationUtil;
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

    private static final String SCOPED_PROXY = "io.micronaut.runtime.context.scope.ScopedProxy";

    private CdiScopes() {
    }

    /**
     * The record of the scope a bean was written with.
     *
     * <p>Whether the scope is normal is written whichever it is. The metadata of a producer carries what the class
     * that declares it carries behind its own, and a member the producer's record left out is read from the class's:
     * a dependent producer of a normal scoped class would be taken for a normal scoped one.</p>
     *
     * @param scope  The name of the scope annotation
     * @param normal Whether it is a normal scope
     * @return The record
     */
    public static AnnotationValue<CdiScope> recordOf(String scope, boolean normal) {
        return AnnotationValue.builder(CdiScope.class).value(scope).member("normal", normal).build();
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
            default -> normal ? servedByItsOwnContext(scope) : List.of(recordOf(scope, false));
        };
    }

    /**
     * Whether a scope is a normal one other than the specification's own, which the context registered for it
     * serves.
     *
     * @param scope  The name of the scope annotation
     * @param normal Whether it is a normal scope
     * @return Whether the context registered for it serves it
     */
    public static boolean isServedByItsOwnContext(String scope, boolean normal) {
        return normal && !Cdi.APPLICATION_SCOPED.equals(scope) && !Cdi.REQUEST_SCOPED.equals(scope);
    }

    /**
     * What a bean of a normal scope other than the specification's own is annotated with: the scope's own
     * annotation as the first Micronaut scope of the bean, proxied, and the application scope behind it.
     *
     * <p>Such a scope is served by the context registered for it, which a build compatible extension registers as
     * the application compiles and a portable extension only as the container starts. Micronaut tries the scopes of
     * a bean in the order its metadata names them, so the context registered for the scope's own annotation serves
     * the bean wherever there is one, active or not, and the application scope holds it in a container that
     * registered none.</p>
     *
     * @param scope The name of the scope annotation
     * @return The annotations
     */
    private static List<AnnotationValue<?>> servedByItsOwnContext(String scope) {
        return List.of(
            AnnotationValue.builder(scope)
                .stereotype(AnnotationValue.builder(AnnotationUtil.SCOPE).build())
                .stereotype(AnnotationValue.builder(SCOPED_PROXY).build())
                .build(),
            AnnotationValue.builder(CdiApplicationScope.class).build(),
            recordOf(scope, true)
        );
    }
}
