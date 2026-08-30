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
 * The Micronaut scope that {@code jakarta.enterprise.context.ApplicationScoped} is read as.
 *
 * <p>One instance of the bean is held for as long as the application runs, which is what a Micronaut singleton
 * holds too. It is a scope of its own rather than the singleton scope because the specification calls the
 * application scope a normal one, and a reference to a bean in a normal scope is a client proxy rather than the
 * instance: {@link ScopedProxy} is what makes Micronaut generate one. The proxy is what lets the reference outlive
 * the instance, and it is also what the specification's own restriction on the bean types of a normal scoped bean
 * is about.</p>
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
public @interface CdiApplicationScope {
}
