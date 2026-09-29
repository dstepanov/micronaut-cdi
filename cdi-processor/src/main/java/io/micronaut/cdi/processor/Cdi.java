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

import io.micronaut.core.annotation.Internal;

/**
 * The names of the annotations of the Jakarta Contexts and Dependency Injection specification.
 *
 * <p>The names are used rather than the classes so that the specification does not have to be on the annotation
 * processor classpath of a build that does not use it.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class Cdi {

    /**
     * {@code jakarta.enterprise.context.ApplicationScoped}.
     */
    public static final String APPLICATION_SCOPED = "jakarta.enterprise.context.ApplicationScoped";

    /**
     * {@code jakarta.enterprise.context.RequestScoped}.
     */
    public static final String REQUEST_SCOPED = "jakarta.enterprise.context.RequestScoped";

    /**
     * {@code jakarta.enterprise.context.SessionScoped}, which belongs to CDI Full.
     */
    public static final String SESSION_SCOPED = "jakarta.enterprise.context.SessionScoped";

    /**
     * {@code jakarta.enterprise.context.ConversationScoped}, which belongs to CDI Full.
     */
    public static final String CONVERSATION_SCOPED = "jakarta.enterprise.context.ConversationScoped";

    /**
     * {@code jakarta.enterprise.context.Dependent}, the pseudo-scope a bean has when it declares none.
     */
    public static final String DEPENDENT = "jakarta.enterprise.context.Dependent";

    /**
     * {@code jakarta.enterprise.context.NormalScope}, the meta-annotation of the scopes whose beans are
     * referenced through a client proxy.
     */
    public static final String NORMAL_SCOPE = "jakarta.enterprise.context.NormalScope";

    /**
     * {@code jakarta.enterprise.inject.Produces}, declaring a producer method or a producer field.
     */
    public static final String PRODUCES = "jakarta.enterprise.inject.Produces";

    /**
     * {@code jakarta.enterprise.inject.Disposes}, marking the parameter a disposer method disposes of.
     */
    public static final String DISPOSES = "jakarta.enterprise.inject.Disposes";

    /**
     * {@code jakarta.enterprise.inject.Typed}, narrowing the bean types of a bean.
     */
    public static final String TYPED = "jakarta.enterprise.inject.Typed";

    /**
     * {@code jakarta.enterprise.inject.Vetoed}, which is how a class says it is not a bean.
     */
    public static final String VETOED = "jakarta.enterprise.inject.Vetoed";

    /**
     * {@code jakarta.enterprise.inject.Alternative}.
     */
    public static final String ALTERNATIVE = "jakarta.enterprise.inject.Alternative";

    /**
     * {@code jakarta.enterprise.inject.Stereotype}, the meta-annotation of the stereotypes.
     */
    public static final String STEREOTYPE = "jakarta.enterprise.inject.Stereotype";

    /**
     * {@code jakarta.enterprise.inject.Specializes}, which belongs to CDI Full.
     */
    public static final String SPECIALIZES = "jakarta.enterprise.inject.Specializes";

    /**
     * {@code jakarta.enterprise.inject.Any}, the qualifier every bean has.
     */
    public static final String ANY = "jakarta.enterprise.inject.Any";

    /**
     * {@code jakarta.enterprise.inject.Default}, the qualifier a bean has when it declares no other.
     */
    public static final String DEFAULT = "jakarta.enterprise.inject.Default";

    /**
     * {@code jakarta.enterprise.inject.TransientReference}.
     */
    public static final String TRANSIENT_REFERENCE = "jakarta.enterprise.inject.TransientReference";

    /**
     * {@code jakarta.enterprise.event.Observes}, marking the event parameter of an observer method.
     */
    public static final String OBSERVES = "jakarta.enterprise.event.Observes";

    /**
     * {@code jakarta.enterprise.event.ObservesAsync}, marking the event parameter of an asynchronous observer
     * method.
     */
    public static final String OBSERVES_ASYNC = "jakarta.enterprise.event.ObservesAsync";

    /**
     * {@code jakarta.enterprise.inject.spi.Prioritized} and the priority of a bean or an observer method, which
     * the specification reads from {@code jakarta.annotation.Priority}.
     */
    public static final String PRIORITY = "jakarta.annotation.Priority";

    /**
     * {@code jakarta.enterprise.util.Nonbinding}, excluding a member of a qualifier from the comparison of
     * qualifiers.
     */
    public static final String NONBINDING = "jakarta.enterprise.util.Nonbinding";

    /**
     * {@code jakarta.inject.Qualifier}, the meta-annotation of the qualifiers.
     */
    public static final String QUALIFIER = "jakarta.inject.Qualifier";

    /**
     * {@code jakarta.enterprise.context.control.ActivateRequestContext}.
     */
    public static final String ACTIVATE_REQUEST_CONTEXT = "jakarta.enterprise.context.control.ActivateRequestContext";

    private Cdi() {
    }

    /**
     * Whether the annotation name is one of the scopes this implementation serves.
     *
     * @param name The annotation name
     * @return Whether it is a scope
     */
    public static boolean isScope(String name) {
        return "jakarta.enterprise.context.ApplicationScoped".equals(name)
            || "jakarta.enterprise.context.RequestScoped".equals(name)
            || DEPENDENT.equals(name)
            || "jakarta.inject.Singleton".equals(name);
    }

    /**
     * Reads the priority an element was selected with.
     *
     * <p>Micronaut maps the priority annotation to its own order, and keeps the annotation beside what it mapped
     * it to, so the priority is read as it was written. An order the element declares itself is not a priority of
     * the specification, and is not read as one.</p>
     *
     * @param metadata The annotation metadata
     * @return The priority, or {@code null} where none was written
     */
    public static java.lang.@org.jspecify.annotations.Nullable Integer priorityOf(
        io.micronaut.core.annotation.AnnotationMetadata metadata) {
        java.util.OptionalInt declared = metadata.intValue(PRIORITY, "value");
        return declared.isPresent() ? declared.getAsInt() : null;
    }
}
