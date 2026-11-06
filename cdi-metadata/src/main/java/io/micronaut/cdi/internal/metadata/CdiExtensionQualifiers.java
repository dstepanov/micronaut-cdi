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
package io.micronaut.cdi.internal.metadata;

import io.micronaut.core.annotation.Internal;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The annotations the build compatible extensions of one compilation made qualifiers of, recorded on the
 * definition of a {@link CdiDeploymentRecord}: an annotation an extension registered says nothing about it on its
 * own class, and no bean may
 * use it, so this is the one witness of it the container reads its qualifier types from.
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Internal
public @interface CdiExtensionQualifiers {

    /**
     * The name of the member that holds the members of each annotation, and of the member of each record that holds
     * every member of its annotation.
     */
    String MEMBERS = "members";

    /**
     * The annotation names.
     *
     * @return The names
     */
    String[] value();

    /**
     * What the compilation saw of the interface of each annotation named, where it saw it: its members, and the ones
     * that take no part in resolution, as its source and the extensions marked them. A name alone says nothing of
     * the members, so an annotation with no record here is not taken for one without any.
     *
     * @return The members of each annotation
     */
    Members[] members() default {};

    /**
     * The members of one annotation an extension made a qualifier of, as the compilation saw its interface.
     */
    @Documented
    @Retention(RetentionPolicy.RUNTIME)
    @Target({})
    @interface Members {

        /**
         * The name of the member that holds the annotation name.
         */
        String NAME = "name";

        /**
         * The name of the member that holds the members that take no part in resolution.
         */
        String NONBINDING = "nonbinding";

        /**
         * The annotation name.
         *
         * @return The name
         */
        String name();

        /**
         * Every member the interface declares.
         *
         * @return The member names
         */
        String[] members() default {};

        /**
         * The members that take no part in resolution.
         *
         * @return The member names
         */
        String[] nonbinding() default {};
    }
}
