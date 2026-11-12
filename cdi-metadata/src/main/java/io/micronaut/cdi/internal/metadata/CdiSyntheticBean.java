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
 * One synthetic bean a build compatible extension described in its synthesis phase (section 2.10.5), recorded
 * while the application compiled.
 *
 * <p>The phase runs inside the compiler, and what it describes is written as the annotation metadata of a bean
 * definition generated for the creator class the extension named. The container reads the record as it starts,
 * registers the bean it describes, and asks the definition that carries the record for the creator when an
 * instance is needed: nothing is looked up by name, and no class is instantiated reflectively.</p>
 *
 * <p>One member is not declared here, because the language cannot declare it: {@code qualifiers} holds the
 * qualifier annotations of the bean, each of its own annotation type, as nested annotation values, each listing
 * the members that take no part in resolution under {@code $nonBinding} as the compiler lists them.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Internal
public @interface CdiSyntheticBean {

    /**
     * The name of the undeclared member that holds the qualifiers.
     */
    String QUALIFIERS = "qualifiers";

    /**
     * The class the bean is created as.
     *
     * @return The implementation class
     */
    Class<?> implementation();

    /**
     * The classes of the bean types the extension declared, which is all a processor before
     * {@link #declaredTypes()} recorded of them: the container reads the bean types from these where a record has no
     * declared types.
     *
     * @return The classes
     */
    Class<?>[] types() default {};

    /**
     * The bean types the extension declared, each as it declared it: a class by itself, or a parameterized type
     * with its arguments.
     *
     * @return The types
     */
    CdiRecordedType[] declaredTypes() default {};

    /**
     * The annotation types of the qualifiers, in the order of the qualifiers.
     *
     * @return The annotation types
     */
    Class<?>[] qualifierTypes() default {};

    /**
     * The scope annotation of the bean: the one the extension set, or the one a stereotype it named carries.
     * {@code void} stands for the dependent pseudo-scope.
     *
     * @return The scope annotation
     */
    Class<?> scope() default void.class;

    /**
     * Whether the scope is a normal one.
     *
     * @return Whether it is normal
     */
    boolean normal() default false;

    /**
     * The name of the bean, or the empty string where it has none.
     *
     * @return The name
     */
    String name() default "";

    /**
     * The priority of the bean: empty where it has none, one value otherwise.
     *
     * @return The priority
     */
    int[] priority() default {};

    /**
     * Whether the extension declared the bean an alternative.
     *
     * @return Whether it is an alternative
     */
    boolean alternative() default false;

    /**
     * The stereotypes the extension put on the bean.
     *
     * @return The stereotype annotations
     */
    Class<?>[] stereotypes() default {};

    /**
     * The names of the stereotypes among {@link #stereotypes()} that make the bean an alternative.
     *
     * @return The stereotype names
     */
    String[] alternativeStereotypes() default {};

    /**
     * Whether the bean is a reserve (CDI 5.0), declared so or through a stereotype.
     *
     * @return Whether it is a reserve
     */
    boolean reserve() default false;

    /**
     * Whether the bean is eagerly initialized (CDI 5.0), declared so or through a stereotype.
     *
     * @return Whether it is eager
     */
    boolean eager() default false;

    /**
     * Whether the bean is auto-closeable (CDI 5.0), declared so or through a stereotype.
     *
     * @return Whether it is auto-closeable
     */
    boolean autoClose() default false;

    /**
     * The injection points the extension registered for the creation and destruction functions to look up through
     * {@code SyntheticInjections} (CDI 5.0).
     *
     * @return The injection points
     */
    CdiSyntheticInjectionPoint[] injectionPoints() default {};

    /**
     * Whether the creator implements {@code create(SyntheticInjections, Parameters)}, which the compiler read off
     * the class the extension named, rather than the deprecated {@code create(Instance, Parameters)}.
     *
     * @return Whether the creator takes the injections
     */
    boolean creatorTakesInjections() default false;

    /**
     * Whether the disposer implements {@code dispose(T, SyntheticInjections, Parameters)} rather than the
     * deprecated {@code dispose(T, Instance, Parameters)}.
     *
     * @return Whether the disposer takes the injections
     */
    boolean disposerTakesInjections() default false;

    /**
     * Whether the extension named a disposer, whose definition carries {@link CdiSyntheticDisposer} with the
     * same {@link #id()}.
     *
     * @return Whether there is a disposer
     */
    boolean disposer() default false;

    /**
     * What tells this bean from the others the compilation recorded.
     *
     * @return The identifier
     */
    String id();

    /**
     * What the extension attached for the creation and disposal functions to read.
     *
     * @return The parameters
     */
    CdiSyntheticParameter[] params() default {};
}
