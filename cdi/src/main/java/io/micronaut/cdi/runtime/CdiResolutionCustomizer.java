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
package io.micronaut.cdi.runtime;

import io.micronaut.context.BeanResolutionCustomizer;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.type.Argument;
import io.micronaut.inject.BeanDefinition;
import io.micronaut.inject.ProxyBeanDefinition;
import io.micronaut.inject.QualifiedBeanType;

import java.lang.reflect.Type;

/**
 * Micronaut's resolution, widened to the type rules of section 2.4.2.1 where the two disagree.
 *
 * <p>Micronaut's own candidate test answers most lookups; what it does not know are the specification's rules
 * for a bean whose type carries type variables — a generic dependent bean is a candidate for whatever fits its
 * bounds, and a raw producer for a required type that says nothing. When Micronaut says no to a lookup with
 * type arguments, the specification's own matching gets the last word.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class CdiResolutionCustomizer implements BeanResolutionCustomizer {

    private static final java.util.Map<Class<?>, Object> PRIMITIVE_DEFAULTS = java.util.Map.of(
        boolean.class, false,
        byte.class, (byte) 0,
        short.class, (short) 0,
        int.class, 0,
        long.class, 0L,
        float.class, 0f,
        double.class, 0d,
        char.class, '\u0000');

    private static final java.util.Map<Class<?>, Class<?>> BOXED = java.util.Map.of(
        boolean.class, Boolean.class,
        byte.class, Byte.class,
        short.class, Short.class,
        int.class, Integer.class,
        long.class, Long.class,
        float.class, Float.class,
        double.class, Double.class,
        char.class, Character.class);

    // the bean types of a definition, worked out once: every parameterized lookup asks them of every candidate,
    // and working them out walks the class's supertypes. Definitions are compared by identity here — two runtime
    // definitions of one class are equal to each other, and are not the same bean — and the map lives as long
    // as the context this customizer was built for
    private final java.util.concurrent.ConcurrentHashMap<DefinitionKey, java.util.Set<Type>> beanTypes =
        new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    public Argument<?> resolveBeanLookupArgument(Argument<?> beanType) {
        // section 2.1.2 counts a primitive and the class that boxes it as one bean type; Micronaut keeps them
        // apart, so a primitive lookup is made as the boxed one
        Class<?> boxed = BOXED.get(beanType.getType());
        if (boxed != null) {
            return Argument.of(boxed, beanType.getAnnotationMetadata(), (Class<?>[]) null);
        }
        return beanType;
    }

    @Override
    public boolean shouldResolveArrayAsBean(Argument<?> injectionPoint) {
        // an array is a bean type of its own in the specification: an injection point of an array type is
        // satisfied by a producer of the array, never by collecting the beans of the component type
        return true;
    }

    @Override
    public <T> java.util.Optional<io.micronaut.inject.BeanDefinition<T>> resolveNonUniqueBean(
        Argument<T> beanType,
        io.micronaut.context.@io.micronaut.core.annotation.Nullable Qualifier<T> qualifier,
        java.util.Collection<io.micronaut.inject.BeanDefinition<T>> candidates) {
        if (qualifier != null) {
            return java.util.Optional.empty();
        }
        // an injection point with no qualifier asks for the default one (section 2.1.3): among candidates of
        // which some are qualified — a synthetic bean an extension qualified, say — the default-qualified one
        // is the one asked for
        java.util.List<io.micronaut.inject.BeanDefinition<T>> defaulted = new java.util.ArrayList<>(2);
        for (io.micronaut.inject.BeanDefinition<T> candidate : candidates) {
            if (candidate.getAnnotationMetadata().hasAnnotation("jakarta.enterprise.inject.Default")
                || CdiQualifiers.declared(candidate.getAnnotationMetadata()).stream()
                    .allMatch(a -> a instanceof jakarta.enterprise.inject.Default
                        || a instanceof jakarta.enterprise.inject.Any)) {
                defaulted.add(candidate);
            }
        }
        if (defaulted.size() == 1) {
            return java.util.Optional.of(defaulted.get(0));
        }
        return java.util.Optional.empty();
    }

    @Override
    public java.util.Optional<?> resolveNullBean(Argument<?> requestedBeanType, Argument<?> resolvedBeanType,
                                                 io.micronaut.inject.BeanDefinition<?> beanDefinition) {
        // section 2.5.2.4: a producer that returns null satisfies a primitive injection point with the
        // primitive's default value
        Object primitiveDefault = PRIMITIVE_DEFAULTS.get(requestedBeanType.getType());
        if (primitiveDefault != null) {
            return java.util.Optional.of(primitiveDefault);
        }
        io.micronaut.core.annotation.AnnotationMetadata metadata = beanDefinition.getAnnotationMetadata();
        if (metadata.hasAnnotation("io.micronaut.cdi.annotation.CdiProducer")
            && (metadata.booleanValue("io.micronaut.cdi.annotation.CdiScope", "normal").orElse(false)
                || beanDefinition.isSingleton())) {
            // section 3.2.2: an instance in any scope but dependent is shared, and a null cannot be
            throw new jakarta.enterprise.inject.IllegalProductException(
                "The producer of " + beanDefinition.getBeanType().getName() + " returned null, which only a "
                    + "producer of a dependent instance may");
        }
        return java.util.Optional.empty();
    }

    @Override
    public boolean shouldInitializeBean(io.micronaut.context.BeanResolutionContext resolutionContext,
                                        BeanDefinition<?> beanDefinition, Object bean) {
        // a client proxy delegates every call to the instance its context holds: injecting the proxy's own
        // fields would chase the dependencies the proxy exists to defer, which is how a circular chain of
        // normal-scoped beans deadlocks — and section 4.3 has the proxy break exactly that circle
        if (beanDefinition instanceof ProxyBeanDefinition<?>
            && beanDefinition.getAnnotationMetadata()
                .booleanValue("io.micronaut.cdi.annotation.CdiScope", "normal").orElse(false)) {
            return false;
        }
        return true;
    }

    @Override
    public boolean shouldPreserveLazyProxyTargetResolutionPath(
        io.micronaut.context.BeanResolutionContext resolutionContext, BeanDefinition<?> proxyBeanDefinition) {
        // when a client proxy finally resolves the instance its context holds, that resolution starts a fresh
        // dependency chain: carrying the path the proxy was injected on would read a legal circular chain of
        // normal-scoped beans — the one section 4.3 has the proxy break — as a circular dependency
        if (proxyBeanDefinition.getAnnotationMetadata()
            .booleanValue("io.micronaut.cdi.annotation.CdiScope", "normal").orElse(false)) {
            return false;
        }
        return true;
    }

    @Override
    public boolean isCandidateBean(Argument<?> beanType, QualifiedBeanType<?> candidate) {
        if (beanType.getTypeParameters().length > 0 && candidate instanceof BeanDefinition<?> definition
            && isBeanOfTheSpecification(definition)) {
            // a parameterized lookup of a bean of the specification is answered by the rules of section
            // 2.4.2.1 in both directions: they admit what Micronaut's own matching would not — a variable
            // matched by its bounds — and refuse what it would let through — a variable whose other bounds
            // the required argument does not satisfy
            try {
                java.util.Set<Type> types = beanTypes.computeIfAbsent(new DefinitionKey(definition), key -> {
                    Class<?> beanClass = definition instanceof ProxyBeanDefinition<?> proxy
                        ? proxy.getTargetType() : definition.getBeanType();
                    return CdiBean.typesOf(definition, beanClass);
                });
                return CdiAssignability.isTypeMatching(types, CdiTypes.requiredTypeOf(beanType));
            } catch (RuntimeException | LinkageError e) {
                return candidate.isCandidateBean(beanType);
            }
        }
        return candidate.isCandidateBean(beanType);
    }

    /**
     * Whether the definition was compiled as a bean of the specification, which is what its scope marker says.
     */
    private static boolean isBeanOfTheSpecification(BeanDefinition<?> definition) {
        return definition.getAnnotationMetadata().hasAnnotation("io.micronaut.cdi.annotation.CdiScope")
            || definition.getAnnotationMetadata().hasStereotype("io.micronaut.cdi.annotation.CdiScope");
    }

    /**
     * A definition as a key compared by identity rather than by its own equality.
     *
     * @param definition The definition
     */
    private record DefinitionKey(BeanDefinition<?> definition) {
        @Override
        public boolean equals(Object other) {
            return other instanceof DefinitionKey key && key.definition == definition;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(definition);
        }
    }
}
