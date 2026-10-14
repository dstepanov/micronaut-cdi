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

import io.micronaut.cdi.internal.metadata.CdiProducer;
import io.micronaut.context.BeanResolutionCustomizer;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.type.Argument;
import io.micronaut.inject.BeanDefinition;
import io.micronaut.inject.ProxyBeanDefinition;
import io.micronaut.inject.QualifiedBeanType;


/**
 * Micronaut's resolution, adjusted to the type rules of section 2.4.2.1 where the two disagree.
 *
 * <p>Micronaut's own candidate test answers most lookups; what it does not know are the specification's rules
 * for a bean whose type carries type variables — a generic dependent bean is a candidate for whatever fits its
 * bounds, and a raw producer for a required type that says nothing. When Micronaut says no to a lookup with
 * type arguments, the specification's own matching gets the last word. Arrays also use that matching,
 * including raw array lookups, since their element types must be identical.</p>
 *
 * <p>Only the beans of the specification are resolved by its rules: a lookup whose candidates include a bean of
 * Micronaut's own is left to the customizer this one was installed over, Micronaut's default where there was
 * none, so that adding this module to an application leaves the resolution of its own beans as it was.</p>
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
    // the bean types of a definition, worked out once: every parameterized lookup asks them of every candidate,
    // and working them out walks the class's supertypes. A compiled definition is equal to another of its class
    // and a runtime one only to itself, which is what tells the beans apart; the map lives as long as the context
    // this customizer serves
    private final java.util.concurrent.ConcurrentHashMap<BeanDefinition<?>, java.util.List<Argument<?>>> beanTypes =
        new java.util.concurrent.ConcurrentHashMap<>();

    private final BeanResolutionCustomizer delegate;
    // the context this customizer serves, which Micronaut hands it before resolving anything (forContext); none for
    // the one installed on the builder, which only hands out the customizer of each context it builds
    private final io.micronaut.context.@org.jspecify.annotations.Nullable BeanContext beanContext;
    // the generic hierarchies of that context, read from it as the first parameterized lookup is made
    private volatile @org.jspecify.annotations.Nullable RecordedTypeIndex typeIndex;

    /**
     * @param delegate The customizer installed before this one, which resolves what is not the specification's
     */
    CdiResolutionCustomizer(BeanResolutionCustomizer delegate) {
        this(delegate, null);
    }

    private CdiResolutionCustomizer(BeanResolutionCustomizer delegate,
                                    io.micronaut.context.@org.jspecify.annotations.Nullable BeanContext beanContext) {
        this.delegate = delegate;
        this.beanContext = beanContext;
    }

    /**
     * The customizer of one context, which holds the context and the bean types it worked out for it. One builder
     * may build several contexts, and a context that reads a bootstrap environment builds another from its
     * configuration: each is served by a customizer of its own, over the one the customizer installed before this
     * one hands that context.
     *
     * @param beanContext The context, not yet resolving anything
     * @return The customizer that serves it
     */
    @Override
    public BeanResolutionCustomizer forContext(io.micronaut.context.BeanContext beanContext) {
        return new CdiResolutionCustomizer(delegate.forContext(beanContext), beanContext);
    }

    /**
     * The generic hierarchies of the context this customizer serves. They are beans of the context, and a
     * parameterized lookup is made once the context is resolving beans, so they are read from it the first time
     * one is: from the first lookup on, it is matched by them.
     */
    private @org.jspecify.annotations.Nullable RecordedTypeIndex typeIndex() {
        RecordedTypeIndex index = typeIndex;
        if (index == null && beanContext != null) {
            index = RecordedTypeIndex.of(beanContext);
            typeIndex = index;
        }
        return index;
    }

    @Override
    public Argument<?> resolveBeanLookupArgument(Argument<?> beanType) {
        // section 2.1.2 counts a primitive and the class that boxes it as one bean type; Micronaut keeps them
        // apart, so a primitive lookup is made as the boxed one
        Class<?> raw = beanType.getType();
        if (raw.isPrimitive() && raw != void.class) {
            Class<?> boxed = CdiTypes.boxedOf(raw);
            return Argument.of(boxed, beanType.getAnnotationMetadata(), (Class<?>[]) null);
        }
        return delegate.resolveBeanLookupArgument(beanType);
    }

    @Override
    public boolean shouldResolveArrayAsBean(Argument<?> injectionPoint) {
        // an array is a bean type of its own in the specification, but an injection point of a bean of the
        // specification is marked as it compiles to be resolved by the container (ContainerInjectionVisitor): one
        // reaching Micronaut's own collecting is a bean of Micronaut's
        return delegate.shouldResolveArrayAsBean(injectionPoint);
    }

    @Override
    public <T> java.util.Optional<io.micronaut.inject.BeanDefinition<T>> resolveNonUniqueBean(
        Argument<T> beanType,
        io.micronaut.context.@org.jspecify.annotations.Nullable Qualifier<T> qualifier,
        java.util.Collection<io.micronaut.inject.BeanDefinition<T>> candidates) {
        if (beanType.getType() == jakarta.inject.Provider.class && candidates.size() == 2
            && candidates.stream().anyMatch(candidate ->
                candidate instanceof io.micronaut.inject.provider.JakartaProviderBeanDefinition)) {
            // Instance extends Provider. Its built-in factory captures the injection point and owns the
            // dependent instances it creates; Micronaut's ordinary Provider does not retain that metadata.
            // Resolve only this overlap between the two built-ins, leaving user-defined providers alone.
            for (BeanDefinition<T> candidate : candidates) {
                if (candidate instanceof CdiInstanceFactory<?>) {
                    return java.util.Optional.of(candidate);
                }
            }
        }
        if (qualifier != null || !candidates.stream().allMatch(CdiResolution::isBeanOfTheSpecification)) {
            // a lookup that names its qualifiers, or one among beans of Micronaut's own - which are not given
            // the default qualifier - is resolved by Micronaut's rules: a primary bean, a secondary one, order
            return delegate.resolveNonUniqueBean(beanType, qualifier, candidates);
        }
        // an injection point with no qualifier asks for the default one (section 2.1.3): among candidates of
        // which some are qualified — a synthetic bean an extension qualified, say — the default-qualified one
        // is the one asked for
        java.util.List<io.micronaut.inject.BeanDefinition<T>> defaulted = new java.util.ArrayList<>(2);
        for (io.micronaut.inject.BeanDefinition<T> candidate : candidates) {
            if (candidate.getAnnotationMetadata().hasAnnotation("jakarta.enterprise.inject.Default")
                || CdiQualifier.declared(candidate.getAnnotationMetadata()).stream()
                    .allMatch(declared -> declared.isDefault() || declared.isAny())) {
                defaulted.add(candidate);
            }
        }
        if (defaulted.size() == 1) {
            return java.util.Optional.of(defaulted.get(0));
        }
        return delegate.resolveNonUniqueBean(beanType, qualifier, candidates);
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
        if (metadata.hasAnnotation(CdiProducer.class)
            && (CdiResolution.isNormalScoped(metadata)
                || beanDefinition.isSingleton())) {
            // section 3.2.2: an instance in any scope but dependent is shared, and a null cannot be
            throw new jakarta.enterprise.inject.IllegalProductException(
                "The producer of " + beanDefinition.getBeanType().getName() + " returned null, which only a "
                    + "producer of a dependent instance may");
        }
        return delegate.resolveNullBean(requestedBeanType, resolvedBeanType, beanDefinition);
    }

    @Override
    public boolean shouldInitializeBean(io.micronaut.context.BeanResolutionContext resolutionContext,
                                        BeanDefinition<?> beanDefinition, Object bean) {
        // a client proxy delegates every call to the instance its context holds: injecting the proxy's own
        // fields would chase the dependencies the proxy exists to defer, which is how a circular chain of
        // normal-scoped beans deadlocks — and section 4.3 has the proxy break exactly that circle
        if (beanDefinition instanceof ProxyBeanDefinition<?>
            && CdiResolution.isNormalScoped(beanDefinition.getAnnotationMetadata())) {
            return false;
        }
        return delegate.shouldInitializeBean(resolutionContext, beanDefinition, bean);
    }

    @Override
    public boolean shouldPreserveLazyProxyTargetResolutionPath(
        io.micronaut.context.BeanResolutionContext resolutionContext, BeanDefinition<?> proxyBeanDefinition) {
        // when a client proxy finally resolves the instance its context holds, that resolution starts a fresh
        // dependency chain: carrying the path the proxy was injected on would read a legal circular chain of
        // normal-scoped beans — the one section 4.3 has the proxy break — as a circular dependency
        if (CdiResolution.isNormalScoped(proxyBeanDefinition.getAnnotationMetadata())) {
            return false;
        }
        return delegate.shouldPreserveLazyProxyTargetResolutionPath(resolutionContext, proxyBeanDefinition);
    }

    @Override
    public boolean isCandidateBean(Argument<?> beanType, QualifiedBeanType<?> candidate) {
        if ((beanType.getTypeParameters().length > 0 || CdiTypes.isArray(beanType))
            && candidate instanceof BeanDefinition<?> definition
            && CdiResolution.isBeanOfTheSpecification(definition)) {
            // a parameterized lookup of a bean of the specification is answered by the rules of section
            // 2.4.2.1 in both directions: they admit what Micronaut's own matching would not — a variable
            // matched by its bounds — and refuse what it would let through — a variable whose other bounds
            // the required argument does not satisfy
            RecordedTypeIndex index = typeIndex();
            try {
                java.util.List<Argument<?>> types = beanTypes.computeIfAbsent(definition, key -> {
                    Class<?> beanClass = definition instanceof ProxyBeanDefinition<?> proxy
                        ? proxy.getTargetType() : definition.getBeanType();
                    return CdiBean.typesOf(index, definition, beanClass);
                });
                return CdiAssignability.isTypeMatching(index, types, beanType);
            } catch (NoClassDefFoundError | TypeNotPresentException e) {
                // a type the bean recorded is of a class absent from the classpath: what Micronaut matches it by
                // is all there is to go on
                return delegate.isCandidateBean(beanType, candidate);
            }
        }
        return delegate.isCandidateBean(beanType, candidate);
    }
}
