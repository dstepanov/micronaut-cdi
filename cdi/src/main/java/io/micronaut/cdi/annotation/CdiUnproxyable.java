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
 * Says that a bean in a normal scope cannot be proxied, and why.
 *
 * <p>Section 3.11 lists what a client proxy needs of the class it stands in front of — a reachable constructor
 * without parameters, no final class, no final methods — and a bean that lacks one of them is still a bean: it
 * deploys, and it fails only when a contextual reference is actually asked for, with the
 * {@code UnproxyableResolutionException} the specification names. The proxy Micronaut would have generated is
 * not generated at all — it could not have been — and this annotation is what remembers why.</p>
 *
 * <p>An injection point that resolves to such a bean is another matter: that is the deployment problem of
 * section 3.11, and the deployment validation reads this annotation to report it.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Internal
public @interface CdiUnproxyable {

    /**
     * Why the bean cannot be proxied.
     *
     * @return The reason
     */
    String value();
}
