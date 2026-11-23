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

import io.micronaut.cdi.fuzz.Scenario.BeanDef;
import io.micronaut.cdi.fuzz.Scenario.DecoratorDef;
import io.micronaut.cdi.fuzz.Scenario.InjectionPointDef;
import io.micronaut.cdi.fuzz.Scenario.InterceptorDef;
import io.micronaut.cdi.fuzz.Scenario.IpKind;
import io.micronaut.cdi.fuzz.Scenario.ObserverDef;
import io.micronaut.cdi.fuzz.Scenario.Probe;
import io.micronaut.cdi.fuzz.Scenario.ProbeKind;
import io.micronaut.cdi.fuzz.Scenario.ProducerDef;
import io.micronaut.cdi.fuzz.Scenario.QualUse;
import io.micronaut.cdi.fuzz.Scenario.Scope;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Generates a scenario from a seed. The same seed and size always give the same scenario.
 *
 * <p>The generator aims for deployments that are mostly valid, so that most runs get as far as the probes, and
 * leaves a small share of the rules of the specification to chance - a {@code @Typed} naming a type the bean does
 * not have, an {@code IF_EXISTS} observer of a dependent bean, an injection point nothing satisfies - so that both
 * implementations are also asked to reject deployments.</p>
 */
final class ScenarioGenerator {

    private static final List<String> NON_GENERIC_SERVICES = List.of("Svc", "S1", "S2", "S3");
    private static final List<Integer> ALTERNATIVE_PRIORITIES = List.of(10, 20, 20, 30);
    private static final List<Integer> OBSERVER_PRIORITIES = List.of(1, 5, 5, 10);
    private static final List<Integer> INTERCEPTOR_PRIORITIES = List.of(100, 200, 200);
    // past the default priority of an observer, Interceptor.Priority.APPLICATION + 500
    private static final int IF_EXISTS_PRIORITY = 5000;
    private static final List<Integer> DECORATOR_PRIORITIES = List.of(10, 20, 20);

    /**
     * Whether decorators are generated: they are CDI Full, so not for this implementation.
     */
    static final boolean GENERATE_DECORATORS = false;

    private final Random random;
    // what was added after the first campaigns draws from a stream of its own, so that a seed still generates the
    // beans, interceptors and probes it generated before, now with these additions
    private final Random extra;
    private final Size size;
    private int producers;
    private int observers;

    /**
     * How large the generated scenarios are.
     */
    enum Size {
        SMALL(3, 2, 6), MEDIUM(6, 2, 10), LARGE(10, 3, 16);

        final int maxBeans;
        final int maxInterceptorsAndDecorators;
        final int maxProbes;

        Size(int maxBeans, int maxInterceptorsAndDecorators, int maxProbes) {
            this.maxBeans = maxBeans;
            this.maxInterceptorsAndDecorators = maxInterceptorsAndDecorators;
            this.maxProbes = maxProbes;
        }
    }

    private ScenarioGenerator(long seed, Size size) {
        this.random = new Random(seed);
        this.extra = new Random(seed * 31 + 17);
        this.size = size;
    }

    static Scenario generate(long seed, Size size) {
        return new ScenarioGenerator(seed, size).scenario(seed);
    }

    private Scenario scenario(long seed) {
        List<BeanDef> beans = new ArrayList<>();
        int beanCount = 1 + random.nextInt(size.maxBeans);
        for (int i = 1; i <= beanCount; i++) {
            beans.add(bean("B" + i, beans));
        }
        List<InterceptorDef> interceptors = new ArrayList<>();
        int interceptorCount = random.nextInt(size.maxInterceptorsAndDecorators + 1);
        for (int i = 1; i <= interceptorCount; i++) {
            interceptors.add(interceptor("I" + i, i));
        }
        // decorators are CDI Full, which this implementation does not claim: none is generated. The draws are
        // still made, so that a seed generates the scenario it generated before decorators were left out
        List<DecoratorDef> decorators = new ArrayList<>();
        int decoratorCount = chance(0.5) ? 0 : random.nextInt(size.maxInterceptorsAndDecorators + 1);
        for (int i = 1; i <= decoratorCount; i++) {
            DecoratorDef unused = new DecoratorDef("D" + i, pick(Scenario.SERVICE_TYPES), delegateQualifiers(),
                pick(DECORATOR_PRIORITIES));
            if (GENERATE_DECORATORS) {
                decorators.add(unused);
            }
        }
        List<Probe> probes = new ArrayList<>();
        int probeCount = 3 + random.nextInt(size.maxProbes - 2);
        List<String> describable = beans.stream().filter(b -> !b.injectionPoints().isEmpty()).map(BeanDef::name).toList();
        for (int i = 1; i <= probeCount; i++) {
            probes.add(probe("X" + i, beans, describable));
        }
        return interception(new Scenario(seed, beans, interceptors, decorators, probes));
    }

    /**
     * The interception a scenario adds on the stream of its own: the bindings of {@code call(String)}, a binding
     * with members, interceptors bound to two bindings, disabled, intercepting construction, short-circuiting,
     * counting, extending a class with an interceptor method of its own, reporting their bindings, and the probes
     * that invoke {@code call(String)}.
     */
    private Scenario interception(Scenario scenario) {
        List<BeanDef> beans = new ArrayList<>();
        for (BeanDef bean : scenario.beans()) {
            List<String> callBindings = extraChance(0.3) ? List.of(extraPick(bindingsOfBeans())) : List.of();
            boolean inheritsCall = bean.superBean() != null && extraChance(0.4);
            List<String> classBindings = new ArrayList<>(bean.bindings());
            if (extraChance(0.12)) {
                String binding = extraPick(bindingsOfBeans());
                if (classBindings.stream().noneMatch(b -> sameBinding(b, binding))) {
                    classBindings.add(binding);
                }
            }
            beans.add(bean.with(b -> {
                b.callBindings = callBindings;
                b.inheritsCall = inheritsCall;
                b.bindings = classBindings;
                return b;
            }));
        }
        List<InterceptorDef> interceptors = new ArrayList<>();
        for (InterceptorDef interceptor : scenario.interceptors()) {
            List<String> bindings = new ArrayList<>(interceptor.bindings());
            if (extraChance(0.25)) {
                bindings.set(0, extraPick(Scenario.ALL_BINDINGS));
            }
            if (extraChance(0.2)) {
                String second = extraPick(Scenario.ALL_BINDINGS);
                if (bindings.stream().noneMatch(b -> sameBinding(b, second))) {
                    bindings.add(second);
                }
            }
            // an interceptor no priority enables is not enabled in CDI Lite
            Integer priority = extraChance(0.08) ? null : interceptor.priority();
            boolean aroundConstruct = extraChance(0.25);
            boolean shortCircuit = interceptor.aroundInvoke() && extraChance(0.08);
            boolean stateful = interceptor.aroundInvoke() && extraChance(0.3);
            boolean base = interceptor.aroundInvoke() && extraChance(0.2);
            boolean reportsBindings = interceptor.aroundInvoke() && extraChance(0.3);
            interceptors.add(interceptor.with(b -> {
                b.bindings = bindings;
                b.priority = priority;
                b.aroundConstruct = aroundConstruct;
                b.shortCircuit = shortCircuit;
                b.stateful = stateful;
                b.base = base;
                b.reportsBindings = reportsBindings;
                return b;
            }));
        }
        List<Probe> probes = new ArrayList<>(scenario.probes());
        int invocations = extraChance(0.7) ? 1 + extra.nextInt(3) : 0;
        for (int i = 1; i <= invocations && !beans.isEmpty(); i++) {
            probes.add(extra.nextInt(probes.size() + 1),
                new Probe("Y" + i, ProbeKind.INVOKE, extraPick(beans).name(), List.of(), extraChance(0.3)));
        }
        return new Scenario(scenario.seed(), beans, interceptors, scenario.decorators(), probes);
    }

    private static List<String> bindingsOfBeans() {
        List<String> all = new ArrayList<>(Scenario.ALL_BINDINGS);
        all.addAll(Scenario.BEAN_ONLY_BINDINGS);
        return all;
    }

    /**
     * Whether two uses are of one binding annotation, which a class or method may declare once.
     */
    static boolean sameBinding(String a, String b) {
        return bindingType(a).equals(bindingType(b));
    }

    static String bindingType(String binding) {
        int paren = binding.indexOf('(');
        return paren < 0 ? binding : binding.substring(0, paren);
    }

    private boolean extraChance(double probability) {
        return extra.nextDouble() < probability;
    }

    private <T> T extraPick(List<T> values) {
        return values.get(extra.nextInt(values.size()));
    }

    private BeanDef bean(String name, List<BeanDef> earlier) {
        boolean generic = chance(0.06);
        Scope scope = generic || chance(0.65) ? Scope.DEPENDENT : Scope.APPLICATION;
        String superBean = null;
        if (!generic && !earlier.isEmpty() && chance(0.2)) {
            BeanDef candidate = pick(earlier);
            if (!candidate.generic()) {
                superBean = candidate.name();
            }
        }
        Set<String> inheritedGen = new LinkedHashSet<>();
        for (BeanDef b = superBean == null ? null : find(earlier, superBean); b != null;
             b = b.superBean() == null ? null : find(earlier, b.superBean())) {
            b.interfaces().stream().filter(t -> t.startsWith("Gen")).forEach(inheritedGen::add);
        }
        List<String> interfaces = new ArrayList<>();
        int interfaceCount = random.nextInt(3);
        for (int i = 0; i < interfaceCount; i++) {
            String type = pick(Scenario.SERVICE_TYPES);
            boolean isGen = type.startsWith("Gen");
            boolean genTaken = generic || !inheritedGen.isEmpty() || interfaces.stream().anyMatch(t -> t.startsWith("Gen"));
            if (!interfaces.contains(type) && !(isGen && genTaken)) {
                interfaces.add(type);
            }
        }
        boolean alternative = chance(0.15);
        Integer priority = alternative ? (chance(0.85) ? pick(ALTERNATIVE_PRIORITIES) : null)
            : (chance(0.05) ? pick(ALTERNATIVE_PRIORITIES) : null);
        List<String> typed = null;
        if (!generic && chance(0.1)) {
            typed = typedSubset(beanTypes(name, superBean, interfaces, earlier));
        }
        List<String> bindings = chance(0.2) ? List.of(pick(Scenario.BINDINGS)) : List.of();
        List<String> methodBindings = chance(0.1) ? List.of(pick(Scenario.BINDINGS)) : List.of();

        List<InjectionPointDef> points = new ArrayList<>();
        if (chance(0.25)) {
            int count = 1 + random.nextInt(3);
            for (int i = 1; i <= count; i++) {
                IpKind kind = pick(List.of(IpKind.values()));
                points.add(injectionPoint("ip" + i, kind, earlier));
            }
        }
        List<ProducerDef> producerDefs = new ArrayList<>();
        if (chance(0.25)) {
            int count = 1 + random.nextInt(2);
            for (int i = 0; i < count; i++) {
                producerDefs.add(producer(earlier));
            }
        }
        List<ObserverDef> observerDefs = new ArrayList<>();
        if (chance(0.3)) {
            int count = 1 + random.nextInt(2);
            for (int i = 0; i < count; i++) {
                boolean ifExists = scope == Scope.DEPENDENT ? chance(0.02) : chance(0.15);
                String eventType = pick(Scenario.OBSERVED_EVENT_TYPES);
                // an observer of every event is static: Weld recurses without end notifying one of a dependent
                // bean that has callbacks, a scenario this fuzzer does not need
                boolean isStatic = eventType.equals("Object") || chance(0.15);
                boolean conditional = ifExists && !isStatic;
                int number = ++observers;
                // a conditional observer is notified after every other, by a priority of its own: one of the
                // priority of an observer that creates its bean may be notified before or after it, either legally
                Integer observerPriority = conditional ? Integer.valueOf(IF_EXISTS_PRIORITY + number)
                    : chance(0.5) ? pick(OBSERVER_PRIORITIES) : null;
                observerDefs.add(new ObserverDef("O" + number, eventType,
                    chance(0.2) ? List.of(qualifier()) : List.of(), observerPriority, conditional, isStatic));
            }
        }
        return new BeanDef(name, scope, interfaces, superBean, generic, chance(0.35) ? qualifiers() : List.of(),
            typed, alternative, priority, bindings, methodBindings, chance(0.3), chance(0.3), chance(0.05), points,
            producerDefs, observerDefs);
    }

    private static BeanDef find(List<BeanDef> beans, String name) {
        return beans.stream().filter(b -> b.name().equals(name)).findFirst().orElse(null);
    }

    /**
     * The bean types of a class bean, as {@code @Typed} names them: its class, the bean classes it extends, its
     * interfaces and theirs, and {@code Object}.
     */
    private static List<String> beanTypes(String name, String superBean, List<String> interfaces, List<BeanDef> earlier) {
        Set<String> types = new LinkedHashSet<>();
        types.add(name);
        Set<String> allInterfaces = new LinkedHashSet<>(interfaces);
        for (BeanDef b = superBean == null ? null : find(earlier, superBean); b != null;
             b = b.superBean() == null ? null : find(earlier, b.superBean())) {
            types.add(b.name());
            allInterfaces.addAll(b.interfaces());
        }
        for (String type : allInterfaces) {
            types.addAll(superInterfaces(type));
        }
        types.add("Object");
        return List.copyOf(types);
    }

    private static List<String> superInterfaces(String type) {
        return switch (type) {
            case "S2" -> List.of("S2", "S1", "Svc");
            case "S1" -> List.of("S1", "Svc");
            case "S3" -> List.of("S3", "Svc");
            case "Svc" -> List.of("Svc");
            default -> List.of(type, "Svc");
        };
    }

    private List<String> typedSubset(List<String> types) {
        List<String> subset = new ArrayList<>();
        for (String type : types) {
            if (chance(0.4)) {
                subset.add(type);
            }
        }
        if (chance(0.03)) {
            // a type the bean does not have: a definition error
            String other = pick(NON_GENERIC_SERVICES);
            if (!types.contains(other)) {
                subset.add(other);
            }
        }
        return subset;
    }

    private InjectionPointDef injectionPoint(String name, IpKind kind, List<BeanDef> earlier) {
        List<BeanDef> targets = earlier.stream().filter(b -> !b.generic() && !b.alternative()).toList();
        if (!targets.isEmpty() && chance(0.85)) {
            // mostly a type and the qualifiers of an earlier bean, so that most injection points are satisfied
            BeanDef target = pick(targets);
            List<String> types = new ArrayList<>(target.interfaces());
            if (kind != IpKind.INSTANCE) {
                types.add(target.name());
            }
            if (!types.isEmpty()) {
                return new InjectionPointDef(name, kind, pick(types), chance(0.9) ? target.qualifiers() : List.of());
            }
        }
        String type;
        if (kind != IpKind.INSTANCE && !earlier.isEmpty() && chance(0.1)) {
            type = pick(earlier).name();
            BeanDef bean = find(earlier, type);
            if (bean != null && bean.generic()) {
                type = pick(Scenario.SERVICE_TYPES);
            }
        } else {
            type = pick(Scenario.SERVICE_TYPES);
        }
        List<QualUse> qualifiers;
        double roll = random.nextDouble();
        if (roll < 0.75) {
            qualifiers = List.of();
        } else if (roll < 0.85) {
            qualifiers = List.of(new QualUse("Any", null, null));
        } else {
            qualifiers = List.of(qualifier());
        }
        return new InjectionPointDef(name, kind, type, qualifiers);
    }

    private ProducerDef producer(List<BeanDef> earlier) {
        String name = "P" + (++producers);
        boolean field = chance(0.3);
        String type = pick(Scenario.SERVICE_TYPES);
        boolean alternative = chance(0.1);
        List<String> typed = null;
        if (chance(0.05)) {
            // Object is left out of a producer's @Typed: Weld leaves it out of the bean types it checks a
            // producer's @Typed against, and the fuzzer does not take the reference implementation's word there
            typed = typedSubset(superInterfaces(type));
        }
        List<InjectionPointDef> parameters = new ArrayList<>();
        if (!field && chance(0.3)) {
            parameters.add(injectionPoint("arg1", IpKind.FIELD, earlier));
        }
        return new ProducerDef(name, field, chance(0.15), chance(0.25) ? Scope.APPLICATION : Scope.DEPENDENT, type,
            chance(0.4) ? qualifiers() : List.of(), typed, alternative,
            alternative ? pick(ALTERNATIVE_PRIORITIES) : null, chance(0.3), parameters);
    }

    private InterceptorDef interceptor(String name, int index) {
        boolean aroundInvoke = chance(0.8);
        boolean postConstruct = chance(0.3);
        boolean preDestroy = chance(0.3);
        if (!aroundInvoke && !postConstruct && !preDestroy) {
            aroundInvoke = true;
        }
        // no two of one priority: the order of interceptors of one priority is not specified (Jakarta Interceptors
        // 2.2 section 5.2.1), and either nesting is legal
        return new InterceptorDef(name, List.of(pick(Scenario.BINDINGS)), pick(INTERCEPTOR_PRIORITIES) + index,
            aroundInvoke, postConstruct, preDestroy);
    }

    private List<QualUse> delegateQualifiers() {
        double roll = random.nextDouble();
        if (roll < 0.6) {
            return List.of();
        }
        if (roll < 0.85) {
            return List.of(new QualUse("Any", null, null));
        }
        return List.of(qualifier());
    }

    private Probe probe(String name, List<BeanDef> beans, List<String> describable) {
        double roll = random.nextDouble();
        if (roll < 0.45) {
            String type = chance(0.2) ? pick(beans).name() : pick(Scenario.SERVICE_TYPES);
            return new Probe(name, ProbeKind.SELECT, type, probeQualifiers(), chance(0.3));
        }
        if (roll < 0.6) {
            return new Probe(name, ProbeKind.ITERATE, pick(Scenario.SERVICE_TYPES), probeQualifiers(), false);
        }
        if (roll < 0.75 && !describable.isEmpty()) {
            return new Probe(name, ProbeKind.DESCRIBE, pick(describable), List.of(), false);
        }
        return new Probe(name, ProbeKind.FIRE, pick(Scenario.FIRED_EVENT_TYPES),
            chance(0.25) ? List.of(qualifier()) : List.of(), false);
    }

    private List<QualUse> probeQualifiers() {
        double roll = random.nextDouble();
        if (roll < 0.5) {
            return List.of();
        }
        if (roll < 0.65) {
            return List.of(new QualUse("Any", null, null));
        }
        if (roll < 0.7) {
            return List.of(new QualUse("Default", null, null));
        }
        return List.of(qualifier());
    }

    private List<QualUse> qualifiers() {
        List<QualUse> result = new ArrayList<>();
        result.add(qualifier());
        if (chance(0.25)) {
            QualUse second = qualifier();
            if (!second.name().equals(result.get(0).name())) {
                result.add(second);
            }
        }
        return result;
    }

    private QualUse qualifier() {
        String name = pick(List.of("Q1", "Q2", "Q3"));
        return switch (name) {
            case "Q1" -> new QualUse("Q1", null, null);
            case "Q2" -> new QualUse("Q2", pick(List.of("a", "b")), null);
            default -> new QualUse("Q3", pick(List.of("a", "b")), pick(List.of("x", "y")));
        };
    }

    private boolean chance(double probability) {
        return random.nextDouble() < probability;
    }

    private <T> T pick(List<T> values) {
        return values.get(random.nextInt(values.size()));
    }
}
