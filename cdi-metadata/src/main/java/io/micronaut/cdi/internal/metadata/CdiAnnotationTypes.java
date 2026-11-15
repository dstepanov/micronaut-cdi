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
 * What the processor saw of the qualifier and interceptor binding types a class uses where it is injected or where
 * a method of it is invoked - its constructors, fields and methods, inherited ones included, and their parameters -
 * recorded on the class: which annotation types are qualifiers and which are bindings, the members of each as the
 * compilation saw its interface, the ones that take no part in resolution, and which are retained at runtime.
 *
 * <p>The class level metadata of a bean is read without loading its definition, so the container learns these facts
 * of every type the beans use from the references of the definitions alone, rather than by loading every definition
 * to read its injection points and executable methods. The processor writes it on every class it visits, so its
 * presence also says that the class was compiled with this processor.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Internal
public @interface CdiAnnotationTypes {

    /**
     * The name of the member that holds the qualifier types.
     */
    String QUALIFIERS = "qualifiers";

    /**
     * The name of the member that holds the interceptor binding types.
     */
    String BINDINGS = "bindings";

    /**
     * The name of the member that holds the types retained at runtime.
     */
    String RUNTIME = "runtime";

    /**
     * The qualifier types the class uses.
     *
     * @return The annotation names
     */
    String[] qualifiers() default {};

    /**
     * The interceptor binding types the class uses.
     *
     * @return The annotation names
     */
    String[] bindings() default {};

    /**
     * Those of the types named that are retained at runtime.
     *
     * @return The annotation names
     */
    String[] runtime() default {};

    /**
     * The members of each type named, as the compilation saw its interface.
     *
     * @return The members of each type
     */
    CdiExtensionQualifiers.Members[] members() default {};
}
