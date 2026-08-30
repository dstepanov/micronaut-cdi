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

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Records which scope of the specification a bean was written with, after that scope has been read as a Micronaut
 * one.
 *
 * <p>The scope a bean declares is rewritten into the Micronaut scope of the same meaning, because that is what
 * Micronaut resolves the bean in. What the bean was written with is still worth keeping: the container reports the
 * scope of a bean, and the report has to name the annotation the author wrote rather than the one it was read as.
 * That is what this records.</p>
 *
 * <p>It is recorded rather than the annotation of the specification being kept beside the Micronaut one, because
 * some of those annotations are scopes in the sense Micronaut means as well — {@code Dependent} is annotated
 * {@code jakarta.inject.Scope} — and keeping one would leave the bean declaring two scopes, the second of which
 * has no context to be resolved in.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@Internal
public @interface CdiScope {

    /**
     * The name of the scope annotation the bean was written with.
     *
     * @return The fully qualified name of the scope annotation
     */
    String value();

    /**
     * Whether the scope is a normal one, which is to say that a reference to a bean in it is a client proxy
     * rather than the instance. A pseudo-scope, of which the dependent one is the only one here, is not.
     *
     * @return Whether the scope is normal
     */
    boolean normal() default false;
}
