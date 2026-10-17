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
 * Lists, on an annotation interface as the compilation holds it, the members a build compatible extension marked
 * {@code Nonbinding} while the compilation was discovering its classes, which the source of the interface does not
 * say.
 *
 * <p>The members are marked on themselves as well. The list beside them on the interface is what an occurrence of the
 * annotation is completed from: the interface alone is read for it, not the metadata of its members, whose
 * annotations may name the annotation being read.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.ANNOTATION_TYPE)
@Internal
public @interface CdiNonbindingMembers {

    /**
     * @return The names of the members
     */
    String[] value();
}
