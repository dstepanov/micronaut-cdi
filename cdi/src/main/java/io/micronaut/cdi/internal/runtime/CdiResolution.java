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
package io.micronaut.cdi.internal.runtime;

import io.micronaut.cdi.internal.metadata.CdiApplicationScope;
import io.micronaut.cdi.internal.metadata.CdiRequestScope;
import io.micronaut.cdi.internal.metadata.CdiScope;
import io.micronaut.core.annotation.Internal;
import io.micronaut.inject.BeanDefinition;
import io.micronaut.inject.ProxyBeanDefinition;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/**
 * The one rule of section 2.4.2 that picks a bean from several candidates: the alternatives outrank everything
 * else, and among the alternatives the highest priority wins. Anything short of a single winner is an ambiguity.
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class CdiResolution {

    /**
     * The parameters Micronaut adds to the constructor of the subclass it generates for an intercepted bean, after
     * the ones the author wrote: the resolution context, the bean context, the qualifier, the interceptors and
     * the interceptor registry.
     */
    private static final java.util.Set<String> GENERATED_CONSTRUCTOR_PARAMETERS = java.util.Set.of(
        "$beanResolutionContext", "$beanContext", "$qualifier", "$interceptors", "$interceptorRegistry");

    private CdiResolution() {
    }

    /**
     * Whether the argument is something the container hands a generated constructor rather than an injection
     * point the author wrote. It is told by what it is - one of the parameters Micronaut generates - and not by
     * the package of its type: an application may well inject a type of its own from a package of that name.
     *
     * @param argument The argument of a constructor
     * @return Whether Micronaut generated it
     */
    public static boolean isGeneratedConstructorParameter(io.micronaut.core.type.Argument<?> argument) {
        return GENERATED_CONSTRUCTOR_PARAMETERS.contains(argument.getName());
    }

    /**
     * Narrows the candidates the way resolution does. The result has one element where resolution succeeds, and
     * more where it is ambiguous.
     *
     * @param candidates The candidate definitions
     * @param <T>        The bean type
     * @return The narrowed candidates
     */
    public static <T extends BeanDefinition<?>> List<T> narrow(Collection<T> candidates) {
        // the priority a selected alternative was written with is carried as its order, negated: an order
        // prefers the lowest where a priority prefers the highest
        return narrow(candidates, CdiResolution::isAlternative, CdiResolution::priorityOf);
    }

    /**
     * Narrows the candidates the way resolution does, whatever they are: the beans the bean container is handed
     * as well as the definitions the container reads. The result has one element where resolution succeeds, and
     * more where it is ambiguous.
     *
     * @param candidates  The candidates
     * @param alternative Whether a candidate is an alternative
     * @param priority    The priority of an alternative
     * @param <T>         The candidate type
     * @return The narrowed candidates
     */
    public static <T> List<T> narrow(Collection<T> candidates, Predicate<? super T> alternative,
                                     ToIntFunction<? super T> priority) {
        if (candidates.size() <= 1) {
            return List.copyOf(candidates);
        }
        List<T> alternatives = new ArrayList<>();
        for (T candidate : candidates) {
            if (alternative.test(candidate)) {
                alternatives.add(candidate);
            }
        }
        if (alternatives.isEmpty()) {
            return List.copyOf(candidates);
        }
        List<T> best = new ArrayList<>();
        int bestPriority = Integer.MIN_VALUE;
        for (T candidate : alternatives) {
            int candidatePriority = priority.applyAsInt(candidate);
            if (candidatePriority > bestPriority) {
                bestPriority = candidatePriority;
                best.clear();
            }
            if (candidatePriority == bestPriority) {
                best.add(candidate);
            }
        }
        return List.copyOf(best);
    }

    /**
     * Whether the definition is an alternative, written as one or through a stereotype.
     *
     * @param definition The definition
     * @return Whether it is an alternative
     */
    public static boolean isAlternative(BeanDefinition<?> definition) {
        return definition.getAnnotationMetadata().hasStereotype("jakarta.enterprise.inject.Alternative");
    }

    /**
     * The beans among the definitions a lookup resolved, one entry per bean.
     *
     * <p>The definition Micronaut compiles for an abstract class is not a bean: section 3.1.1 requires the class
     * of a managed bean to be concrete, however it is annotated. And the definition a proxy stands in front of
     * describes the same bean as the proxy, so it is dropped in favour of the proxy.</p>
     *
     * @param definitions The definitions
     * @param <D>         The definition type
     * @return The beans, the given collection itself where it has nothing to drop
     */
    public static <D extends BeanDefinition<?>> Collection<D> beansAmong(Collection<D> definitions) {
        Set<String> proxied = new HashSet<>();
        boolean abstractClasses = false;
        for (D definition : definitions) {
            if (definition instanceof ProxyBeanDefinition<?> proxy) {
                proxied.add(proxy.getTargetDefinitionType().getName());
            }
            abstractClasses |= definition.isAbstract();
        }
        if (proxied.isEmpty() && !abstractClasses) {
            return definitions;
        }
        List<D> beans = new ArrayList<>(definitions.size());
        for (D definition : definitions) {
            boolean target = !(definition instanceof ProxyBeanDefinition<?>)
                && proxied.contains(definition.getClass().getName());
            if (definition.isAbstract() || target) {
                continue;
            }
            beans.add(definition);
        }
        return beans;
    }

    /**
     * Whether a failure is a class absent from the classpath, anywhere in its causes: what reading a definition or
     * the types it recorded fails with where it names a class of an optional dependency the application does not
     * have, which is no bean rather than a broken one.
     *
     * @param failure The failure
     * @return Whether a class is absent
     */
    public static boolean isAbsentClass(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof NoClassDefFoundError || cause instanceof ClassNotFoundException
                || cause instanceof TypeNotPresentException) {
                return true;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return false;
    }

    /**
     * Whether the definition was compiled as a bean of the specification - one written with a scope of the
     * specification, or given one by default - rather than as a bean of Micronaut's own that only shares the
     * context: the scope marker the processor writes says which.
     *
     * @param definition The definition
     * @return Whether it is a bean of the specification
     */
    public static boolean isBeanOfTheSpecification(BeanDefinition<?> definition) {
        return definition.getAnnotationMetadata().hasStereotype(CdiScope.class);
    }

    /**
     * Whether a bean, or a producer, was compiled into a normal scope of the specification, which is what makes
     * it reached through a client proxy.
     *
     * @param metadata The annotation metadata of its definition
     * @return Whether its scope is normal
     */
    public static boolean isNormalScoped(io.micronaut.core.annotation.AnnotationMetadata metadata) {
        return metadata.booleanValue(CdiScope.class, "normal").orElse(false);
    }

    /**
     * Whether the bean of the definition is in the dependent pseudo-scope, which is what makes an instance of
     * it belong to whoever asked for it.
     *
     * @param definition The definition
     * @return Whether it is dependent
     */
    public static boolean isDependent(BeanDefinition<?> definition) {
        if (definition.isSingleton()) {
            return false;
        }
        // the scope the bean was described with, where one was recorded — a synthetic bean's metadata names
        // it without carrying the compiled stereotypes
        String declared = definition.getAnnotationMetadata()
            .stringValue(CdiScope.class).orElse(null);
        if (declared != null) {
            return "jakarta.enterprise.context.Dependent".equals(declared);
        }
        // a bean of Micronaut's own held by a scope of Micronaut's - a custom scope - is shared by whoever looks it
        // up, and closing what a lookup of it returned would destroy the instance its scope holds
        Class<? extends java.lang.annotation.Annotation> scope = definition.getScope().orElse(null);
        if (scope != null && scope != io.micronaut.context.annotation.Prototype.class) {
            return false;
        }
        return !definition.getAnnotationMetadata()
            .hasStereotype(CdiApplicationScope.class)
            && !definition.getAnnotationMetadata()
            .hasStereotype(CdiRequestScope.class);
    }

    /**
     * The priority a bean was selected with, read back from the order it was recorded as.
     *
     * @param definition The definition
     * @return The priority
     */
    public static int priorityOf(BeanDefinition<?> definition) {
        // the priority itself where the metadata kept it — zero is a legal priority, which the order encoding
        // alone cannot say apart from none
        java.util.OptionalInt priority = definition.getAnnotationMetadata()
            .intValue("jakarta.annotation.Priority", io.micronaut.core.annotation.AnnotationMetadata.VALUE_MEMBER);
        if (priority.isPresent()) {
            return priority.getAsInt();
        }
        int order = definition.getOrder();
        return order == 0 ? Integer.MIN_VALUE : -order;
    }
}
