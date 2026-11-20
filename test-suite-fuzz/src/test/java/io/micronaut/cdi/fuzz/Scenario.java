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
package io.micronaut.cdi.fuzz;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * A generated deployment: the beans, producers, interceptors, decorators and observers it declares, and the probes
 * the driver runs against the started container.
 *
 * <p>The fixed vocabulary every scenario shares - the service interfaces, the qualifiers, the interceptor bindings
 * and the event types - is rendered whether or not it is used, so that shrinking never has to remove a type that
 * something still names. Everything a scenario varies lives in the lists of this record, and every element of
 * them has a name that stays the same as the scenario shrinks, which is what keeps a trace comparable between a
 * scenario and its reductions.</p>
 *
 * @param seed         The seed the scenario was generated from
 * @param beans        The managed beans
 * @param interceptors The interceptors
 * @param decorators   The decorators
 * @param probes       What the driver does once the container is up, in order
 */
record Scenario(long seed, List<BeanDef> beans, List<InterceptorDef> interceptors, List<DecoratorDef> decorators,
                List<Probe> probes) {

    /**
     * The service interfaces, by the name the sources declare them with. {@code Svc} is the root every other one
     * extends; {@code S2} extends {@code S1}.
     */
    static final List<String> SERVICE_TYPES = List.of("Svc", "S1", "S2", "S3", "Gen<String>", "Gen<Integer>");

    /**
     * The event types an observer may observe or a probe may fire.
     */
    static final List<String> FIRED_EVENT_TYPES = List.of("Ev", "EvA", "EvB", "GEv<String>", "GEv<Integer>");
    static final List<String> OBSERVED_EVENT_TYPES = List.of("Ev", "EvA", "EvB", "Object", "GEv<String>",
        "GEv<Integer>", "GEv<?>", "Startup", "Shutdown");

    /**
     * The interceptor bindings. {@code Bind1} is {@code @Inherited}, {@code Bind2} is not.
     */
    static final List<String> BINDINGS = List.of("Bind1", "Bind2");

    Scenario {
        beans = List.copyOf(beans);
        interceptors = List.copyOf(interceptors);
        decorators = List.copyOf(decorators);
        probes = List.copyOf(probes);
    }

    Scenario withBeans(List<BeanDef> value) {
        return new Scenario(seed, value, interceptors, decorators, probes);
    }

    Scenario withInterceptors(List<InterceptorDef> value) {
        return new Scenario(seed, beans, value, decorators, probes);
    }

    Scenario withDecorators(List<DecoratorDef> value) {
        return new Scenario(seed, beans, interceptors, value, probes);
    }

    Scenario withProbes(List<Probe> value) {
        return new Scenario(seed, beans, interceptors, decorators, value);
    }

    /**
     * The scenario without a bean, and without everything that named its class: the subclasses that extended it
     * extend nothing, a {@code @Typed} that listed it no longer does, and the injection points and probes that
     * looked it up are gone.
     */
    Scenario withoutBean(String name) {
        List<BeanDef> remaining = new ArrayList<>();
        for (BeanDef bean : beans) {
            if (bean.name().equals(name)) {
                continue;
            }
            BeanDef kept = bean;
            if (name.equals(kept.superBean())) {
                kept = kept.withSuperBean(null);
            }
            kept = kept.mapTypeReferences(name);
            remaining.add(kept);
        }
        List<Probe> keptProbes = probes.stream().filter(p -> !p.type().equals(name)).toList();
        return new Scenario(seed, remaining, interceptors, decorators, keptProbes);
    }

    /**
     * The beans a bean class is a subclass of, nearest first.
     */
    List<BeanDef> superBeans(BeanDef bean) {
        List<BeanDef> chain = new ArrayList<>();
        String next = bean.superBean();
        while (next != null) {
            BeanDef found = bean(next);
            if (found == null || chain.contains(found)) {
                break;
            }
            chain.add(found);
            next = found.superBean();
        }
        return chain;
    }

    BeanDef bean(String name) {
        for (BeanDef bean : beans) {
            if (bean.name().equals(name)) {
                return bean;
            }
        }
        return null;
    }

    /**
     * How many elements the scenario has, the measure a reduction has to decrease.
     */
    int size() {
        int size = interceptors.size() + decorators.size() + probes.size();
        for (BeanDef bean : beans) {
            size += 1 + bean.producers().size() + bean.observers().size() + bean.injectionPoints().size()
                + bean.qualifiers().size() + bean.bindings().size() + bean.methodBindings().size();
        }
        return size;
    }

    /**
     * The scopes a generated bean may have. The request scope is left out: its context is not active where the
     * driver runs, and what the two containers do about that is not what this fuzzer looks for.
     */
    enum Scope {
        DEPENDENT("jakarta.enterprise.context.Dependent"),
        APPLICATION("jakarta.enterprise.context.ApplicationScoped");

        final String annotation;

        Scope(String annotation) {
            this.annotation = annotation;
        }
    }

    /**
     * A qualifier as it is used: {@code Q1} has no members, {@code Q2} a binding {@code value}, {@code Q3} a binding
     * {@code value} and a {@code @Nonbinding note}. {@code Any} and {@code Default} are the built-in ones.
     *
     * @param name  The qualifier
     * @param value Its {@code value}, where it has one
     * @param note  Its {@code note}, where it has one
     */
    record QualUse(String name, String value, String note) {

        String annotation() {
            return switch (name) {
                case "Q1", "Any", "Default" -> "@" + name;
                case "Q2" -> "@Q2(\"" + value + "\")";
                case "Q3" -> "@Q3(value = \"" + value + "\", note = \"" + note + "\")";
                default -> throw new IllegalStateException(name);
            };
        }

        String literal() {
            return switch (name) {
                case "Q1" -> "new Q1Literal()";
                case "Any" -> "jakarta.enterprise.inject.Any.Literal.INSTANCE";
                case "Default" -> "jakarta.enterprise.inject.Default.Literal.INSTANCE";
                case "Q2" -> "new Q2Literal(\"" + value + "\")";
                case "Q3" -> "new Q3Literal(\"" + value + "\", \"" + note + "\")";
                default -> throw new IllegalStateException(name);
            };
        }
    }

    /**
     * An injection point of a bean, a producer method's parameter, or a field of a bean.
     *
     * @param name       Its name, unique in the bean
     * @param kind       How it is injected
     * @param type       The type it is injected as
     * @param qualifiers Its qualifiers
     */
    record InjectionPointDef(String name, IpKind kind, String type, List<QualUse> qualifiers) {
        InjectionPointDef {
            qualifiers = List.copyOf(qualifiers);
        }
    }

    enum IpKind {
        FIELD, CONSTRUCTOR, INITIALIZER, INSTANCE, PROVIDER
    }

    /**
     * A producer method or field of a bean.
     *
     * @param name            Its name, unique in the scenario; the id its product reports
     * @param field           Whether it is a field rather than a method
     * @param isStatic        Whether it is static
     * @param scope           The scope of its product
     * @param type            The type it produces
     * @param qualifiers      Its qualifiers
     * @param typed           Its {@code @Typed}, or {@code null}
     * @param alternative     Whether it is an alternative
     * @param priority        Its {@code @Priority}, or {@code null}
     * @param disposer        Whether the bean declares a disposer for it
     * @param parameters      The parameters of a producer method, each an injection point
     */
    record ProducerDef(String name, boolean field, boolean isStatic, Scope scope, String type,
                       List<QualUse> qualifiers, List<String> typed, boolean alternative, Integer priority,
                       boolean disposer, List<InjectionPointDef> parameters) {
        ProducerDef {
            qualifiers = List.copyOf(qualifiers);
            typed = typed == null ? null : List.copyOf(typed);
            parameters = List.copyOf(parameters);
        }

        ProducerDef with(UnaryOperator<Builder> change) {
            return change.apply(new Builder(this)).build();
        }

        static final class Builder {
            String name;
            boolean field;
            boolean isStatic;
            Scope scope;
            String type;
            List<QualUse> qualifiers;
            List<String> typed;
            boolean alternative;
            Integer priority;
            boolean disposer;
            List<InjectionPointDef> parameters;

            Builder(ProducerDef p) {
                name = p.name;
                field = p.field;
                isStatic = p.isStatic;
                scope = p.scope;
                type = p.type;
                qualifiers = p.qualifiers;
                typed = p.typed;
                alternative = p.alternative;
                priority = p.priority;
                disposer = p.disposer;
                parameters = p.parameters;
            }

            ProducerDef build() {
                return new ProducerDef(name, field, isStatic, scope, type, qualifiers, typed, alternative, priority,
                    disposer, parameters);
            }
        }
    }

    /**
     * An observer method of a bean.
     *
     * @param name       Its name, unique in the scenario
     * @param eventType  The type it observes
     * @param qualifiers The qualifiers of its event parameter
     * @param priority   Its {@code @Priority}, or {@code null}
     * @param ifExists   Whether it is notified only if an instance exists
     * @param isStatic   Whether it is static
     */
    record ObserverDef(String name, String eventType, List<QualUse> qualifiers, Integer priority, boolean ifExists,
                       boolean isStatic) {
        ObserverDef {
            qualifiers = List.copyOf(qualifiers);
        }
    }

    /**
     * A managed bean.
     *
     * @param name             The name of its class; the id it reports
     * @param scope            Its scope
     * @param interfaces       The service interfaces it implements
     * @param superBean        The bean class it extends, or {@code null}
     * @param generic          Whether it is the generic class {@code Name<T> implements Gen<T>}
     * @param qualifiers       Its qualifiers
     * @param typed            Its {@code @Typed}, or {@code null}
     * @param alternative      Whether it is an alternative
     * @param priority         Its {@code @Priority}, or {@code null}
     * @param bindings         The interceptor bindings of its class
     * @param methodBindings   The interceptor bindings of its {@code id()} method
     * @param postConstruct    Whether it declares a {@code @PostConstruct} callback
     * @param preDestroy       Whether it declares a {@code @PreDestroy} callback
     * @param selfInterceptor  Whether it declares an {@code @AroundInvoke} method of its own
     * @param injectionPoints  Its injection points; a bean with any implements {@code Probe}
     * @param producers        Its producers
     * @param observers        Its observers
     */
    record BeanDef(String name, Scope scope, List<String> interfaces, String superBean, boolean generic,
                   List<QualUse> qualifiers, List<String> typed, boolean alternative, Integer priority,
                   List<String> bindings, List<String> methodBindings, boolean postConstruct, boolean preDestroy,
                   boolean selfInterceptor, List<InjectionPointDef> injectionPoints, List<ProducerDef> producers,
                   List<ObserverDef> observers) {

        BeanDef {
            interfaces = List.copyOf(interfaces);
            qualifiers = List.copyOf(qualifiers);
            typed = typed == null ? null : List.copyOf(typed);
            bindings = List.copyOf(bindings);
            methodBindings = List.copyOf(methodBindings);
            injectionPoints = List.copyOf(injectionPoints);
            producers = List.copyOf(producers);
            observers = List.copyOf(observers);
        }

        BeanDef with(UnaryOperator<Builder> change) {
            return change.apply(new Builder(this)).build();
        }

        BeanDef withSuperBean(String value) {
            return with(b -> {
                b.superBean = value;
                return b;
            });
        }

        /**
         * The bean with every reference to the class of a removed bean dropped.
         */
        BeanDef mapTypeReferences(String removed) {
            Predicate<String> keep = t -> !t.equals(removed);
            return with(b -> {
                if (b.typed != null) {
                    b.typed = b.typed.stream().filter(keep).toList();
                }
                b.injectionPoints = b.injectionPoints.stream().filter(ip -> keep.test(ip.type())).toList();
                b.producers = b.producers.stream()
                    .map(p -> p.with(pb -> {
                        pb.parameters = pb.parameters.stream().filter(ip -> keep.test(ip.type())).toList();
                        return pb;
                    }))
                    .toList();
                return b;
            });
        }

        static final class Builder {
            String name;
            Scope scope;
            List<String> interfaces;
            String superBean;
            boolean generic;
            List<QualUse> qualifiers;
            List<String> typed;
            boolean alternative;
            Integer priority;
            List<String> bindings;
            List<String> methodBindings;
            boolean postConstruct;
            boolean preDestroy;
            boolean selfInterceptor;
            List<InjectionPointDef> injectionPoints;
            List<ProducerDef> producers;
            List<ObserverDef> observers;

            Builder(BeanDef b) {
                name = b.name;
                scope = b.scope;
                interfaces = b.interfaces;
                superBean = b.superBean;
                generic = b.generic;
                qualifiers = b.qualifiers;
                typed = b.typed;
                alternative = b.alternative;
                priority = b.priority;
                bindings = b.bindings;
                methodBindings = b.methodBindings;
                postConstruct = b.postConstruct;
                preDestroy = b.preDestroy;
                selfInterceptor = b.selfInterceptor;
                injectionPoints = b.injectionPoints;
                producers = b.producers;
                observers = b.observers;
            }

            BeanDef build() {
                return new BeanDef(name, scope, interfaces, superBean, generic, qualifiers, typed, alternative,
                    priority, bindings, methodBindings, postConstruct, preDestroy, selfInterceptor, injectionPoints,
                    producers, observers);
            }
        }
    }

    /**
     * An interceptor.
     *
     * @param name          The name of its class
     * @param bindings      Its interceptor bindings, at least one
     * @param priority      Its {@code @Priority}
     * @param aroundInvoke  Whether it intercepts business methods
     * @param postConstruct Whether it intercepts {@code @PostConstruct}
     * @param preDestroy    Whether it intercepts {@code @PreDestroy}
     */
    record InterceptorDef(String name, List<String> bindings, int priority, boolean aroundInvoke,
                          boolean postConstruct, boolean preDestroy) {
        InterceptorDef {
            bindings = List.copyOf(bindings);
        }
    }

    /**
     * A decorator of one service interface.
     *
     * @param name               The name of its class
     * @param type               The type it decorates, which is its delegate's type
     * @param delegateQualifiers The qualifiers of its delegate injection point
     * @param priority           Its {@code @Priority}
     */
    record DecoratorDef(String name, String type, List<QualUse> delegateQualifiers, int priority) {
        DecoratorDef {
            delegateQualifiers = List.copyOf(delegateQualifiers);
        }
    }

    /**
     * What the driver does once the container is up.
     *
     * @param name       Its name, unique in the scenario, logged before what it does
     * @param kind       What it does
     * @param type       The type it looks up or fires, or the bean class it describes
     * @param qualifiers The qualifiers it selects with
     * @param destroy    Whether a looked up instance is destroyed through the {@code Instance} afterwards
     */
    record Probe(String name, ProbeKind kind, String type, List<QualUse> qualifiers, boolean destroy) {
        Probe {
            qualifiers = List.copyOf(qualifiers);
        }
    }

    enum ProbeKind {
        /**
         * {@code Instance.select(type, qualifiers)}: whether it is unsatisfied or ambiguous, and the id of what it
         * gets.
         */
        SELECT,
        /**
         * The ids of everything an {@code Instance} iterates over, in no particular order.
         */
        ITERATE,
        /**
         * {@code describe()} of a bean with injection points, looked up by its class.
         */
        DESCRIBE,
        /**
         * {@code BeanContainer.getEvent().select(type, qualifiers).fire(...)}.
         */
        FIRE
    }
}
