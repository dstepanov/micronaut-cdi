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
package io.micronaut.cdi.annotation;

import io.micronaut.core.annotation.Internal;
import io.micronaut.runtime.context.scope.ScopedProxy;
import jakarta.inject.Scope;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The Micronaut scope that {@code jakarta.enterprise.context.RequestScoped} is read as.
 *
 * <p>The scope of the specification is not written on the bean directly, because Micronaut resolves a custom scope
 * by the annotation its {@link io.micronaut.context.scope.CustomScope} declares, and that annotation has to be one
 * this module owns rather than one the specification does. The mapper rewrites the scope of the specification into
 * this one; everything else about the bean is left as it was written.</p>
 *
 * <p>It is a normal scope in the sense of the specification, so a reference to a bean in it is a client proxy
 * rather than the instance itself: {@link ScopedProxy} is what makes Micronaut generate one, and each invocation
 * through it resolves the instance of the request that is current then. That is what lets a bean of a wider scope
 * hold on to a request scoped one.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD, ElementType.ANNOTATION_TYPE})
@ScopedProxy
@Scope
@Internal
// a scope meta-annotation belongs on an annotation type, which is what this is
@SuppressWarnings("InjectScopeAnnotationOnInterfaceOrAbstractClass")
public @interface CdiRequestScope {
}
