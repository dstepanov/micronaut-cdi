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
import io.micronaut.cdi.fuzz.Scenario.InterceptorDef;
import io.micronaut.cdi.fuzz.Scenario.ObserverDef;
import io.micronaut.cdi.fuzz.Scenario.Probe;
import io.micronaut.cdi.fuzz.Scenario.ProducerDef;
import io.micronaut.cdi.fuzz.Scenario.Scope;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * Reduces a scenario that diverges to a smaller one that diverges the same way.
 *
 * <p>The reduction is greedy: of the candidate reductions of the current scenario - the big ones first, whole
 * halves of the probes, then single beans, interceptors, decorators and probes, then members of a bean, then single
 * annotations - the first that still reproduces is taken, and the search starts again from it. It stops when no
 * candidate reproduces or the budget of runs is spent. Every candidate is strictly smaller or simpler, so the
 * search ends.</p>
 */
final class Shrinker {

    private final Predicate<Scenario> reproduces;
    private final int budget;
    private int runs;

    Shrinker(Predicate<Scenario> reproduces, int budget) {
        this.reproduces = reproduces;
        this.budget = budget;
    }

    int runs() {
        return runs;
    }

    Scenario shrink(Scenario scenario) {
        Scenario current = scenario;
        boolean progress = true;
        while (progress && runs < budget) {
            progress = false;
            for (Scenario candidate : candidates(current)) {
                if (runs >= budget) {
                    break;
                }
                if (candidate.equals(current)) {
                    continue;
                }
                runs++;
                if (reproduces.test(candidate)) {
                    current = candidate;
                    progress = true;
                    break;
                }
            }
        }
        return current;
    }

    /**
     * The reductions of a scenario, the largest first. They are computed lazily in the sense that only a list of
     * scenarios is built, each cheap to construct; a run is what costs.
     */
    static List<Scenario> candidates(Scenario s) {
        List<Scenario> out = new ArrayList<>();
        // halves of the probes
        List<Probe> probes = s.probes();
        if (probes.size() > 1) {
            int half = probes.size() / 2;
            out.add(s.withProbes(probes.subList(half, probes.size())));
            out.add(s.withProbes(probes.subList(0, half)));
        }
        // whole elements
        for (BeanDef bean : s.beans()) {
            out.add(s.withoutBean(bean.name()));
        }
        for (int i = 0; i < s.interceptors().size(); i++) {
            out.add(s.withInterceptors(without(s.interceptors(), i)));
        }
        for (int i = 0; i < s.decorators().size(); i++) {
            out.add(s.withDecorators(without(s.decorators(), i)));
        }
        for (int i = 0; i < probes.size(); i++) {
            out.add(s.withProbes(without(probes, i)));
        }
        // members of a bean
        for (BeanDef bean : s.beans()) {
            for (int i = 0; i < bean.producers().size(); i++) {
                int index = i;
                out.add(replaceBean(s, bean, b -> set(b, x -> x.producers = without(x.producers, index))));
            }
            for (int i = 0; i < bean.observers().size(); i++) {
                int index = i;
                out.add(replaceBean(s, bean, b -> set(b, x -> x.observers = without(x.observers, index))));
            }
            for (int i = 0; i < bean.injectionPoints().size(); i++) {
                int index = i;
                out.add(replaceBean(s, bean, b -> set(b, x -> x.injectionPoints = without(x.injectionPoints, index))));
            }
        }
        // annotations and attributes of a bean
        for (BeanDef bean : s.beans()) {
            if (bean.superBean() != null) {
                out.add(replaceBean(s, bean, b -> set(b, x -> x.superBean = null)));
            }
            if (bean.typed() != null) {
                out.add(replaceBean(s, bean, b -> set(b, x -> x.typed = null)));
            }
            if (bean.alternative()) {
                out.add(replaceBean(s, bean, b -> set(b, x -> x.alternative = false)));
            }
            if (bean.priority() != null) {
                out.add(replaceBean(s, bean, b -> set(b, x -> x.priority = null)));
            }
            if (bean.scope() != Scope.DEPENDENT) {
                out.add(replaceBean(s, bean, b -> set(b, x -> x.scope = Scope.DEPENDENT)));
            }
            if (bean.postConstruct()) {
                out.add(replaceBean(s, bean, b -> set(b, x -> x.postConstruct = false)));
            }
            if (bean.preDestroy()) {
                out.add(replaceBean(s, bean, b -> set(b, x -> x.preDestroy = false)));
            }
            if (bean.selfInterceptor()) {
                out.add(replaceBean(s, bean, b -> set(b, x -> x.selfInterceptor = false)));
            }
            for (int i = 0; i < bean.qualifiers().size(); i++) {
                int index = i;
                out.add(replaceBean(s, bean, b -> set(b, x -> x.qualifiers = without(x.qualifiers, index))));
            }
            for (int i = 0; i < bean.bindings().size(); i++) {
                int index = i;
                out.add(replaceBean(s, bean, b -> set(b, x -> x.bindings = without(x.bindings, index))));
            }
            for (int i = 0; i < bean.methodBindings().size(); i++) {
                int index = i;
                out.add(replaceBean(s, bean, b -> set(b, x -> x.methodBindings = without(x.methodBindings, index))));
            }
            for (int i = 0; i < bean.interfaces().size(); i++) {
                int index = i;
                out.add(replaceBean(s, bean, b -> set(b, x -> x.interfaces = without(x.interfaces, index))));
            }
            if (bean.typed() != null) {
                for (int i = 0; i < bean.typed().size(); i++) {
                    int index = i;
                    out.add(replaceBean(s, bean, b -> set(b, x -> x.typed = without(x.typed, index))));
                }
            }
            for (int i = 0; i < bean.injectionPoints().size(); i++) {
                int index = i;
                var ip = bean.injectionPoints().get(i);
                if (!ip.qualifiers().isEmpty()) {
                    out.add(replaceBean(s, bean, b -> set(b, x -> x.injectionPoints = replace(x.injectionPoints, index,
                        new Scenario.InjectionPointDef(ip.name(), ip.kind(), ip.type(), List.of())))));
                }
            }
            for (int i = 0; i < bean.producers().size(); i++) {
                int index = i;
                for (ProducerDef simpler : simplifications(bean.producers().get(i))) {
                    out.add(replaceBean(s, bean, b -> set(b, x -> x.producers = replace(x.producers, index, simpler))));
                }
            }
            for (int i = 0; i < bean.observers().size(); i++) {
                int index = i;
                for (ObserverDef simpler : simplifications(bean.observers().get(i))) {
                    out.add(replaceBean(s, bean, b -> set(b, x -> x.observers = replace(x.observers, index, simpler))));
                }
            }
        }
        for (int i = 0; i < s.interceptors().size(); i++) {
            InterceptorDef in = s.interceptors().get(i);
            int index = i;
            Function<InterceptorDef, Scenario> with = r -> s.withInterceptors(replace(s.interceptors(), index, r));
            if (in.postConstruct()) {
                out.add(with.apply(new InterceptorDef(in.name(), in.bindings(), in.priority(), in.aroundInvoke(), false, in.preDestroy())));
            }
            if (in.preDestroy()) {
                out.add(with.apply(new InterceptorDef(in.name(), in.bindings(), in.priority(), in.aroundInvoke(), in.postConstruct(), false)));
            }
            if (in.aroundInvoke() && (in.postConstruct() || in.preDestroy())) {
                out.add(with.apply(new InterceptorDef(in.name(), in.bindings(), in.priority(), false, in.postConstruct(), in.preDestroy())));
            }
        }
        for (int i = 0; i < s.decorators().size(); i++) {
            DecoratorDef d = s.decorators().get(i);
            if (!d.delegateQualifiers().isEmpty()) {
                out.add(s.withDecorators(replace(s.decorators(), i, new DecoratorDef(d.name(), d.type(), List.of(), d.priority()))));
            }
        }
        for (int i = 0; i < probes.size(); i++) {
            Probe p = probes.get(i);
            if (!p.qualifiers().isEmpty()) {
                out.add(s.withProbes(replace(probes, i, new Probe(p.name(), p.kind(), p.type(), List.of(), p.destroy()))));
            }
            if (p.destroy()) {
                out.add(s.withProbes(replace(probes, i, new Probe(p.name(), p.kind(), p.type(), p.qualifiers(), false))));
            }
        }
        return out;
    }

    private static List<ProducerDef> simplifications(ProducerDef p) {
        List<ProducerDef> out = new ArrayList<>();
        for (int i = 0; i < p.parameters().size(); i++) {
            int index = i;
            out.add(p.with(b -> {
                b.parameters = without(b.parameters, index);
                return b;
            }));
        }
        for (int i = 0; i < p.qualifiers().size(); i++) {
            int index = i;
            out.add(p.with(b -> {
                b.qualifiers = without(b.qualifiers, index);
                return b;
            }));
        }
        if (p.typed() != null) {
            out.add(p.with(b -> {
                b.typed = null;
                return b;
            }));
        }
        if (p.alternative()) {
            out.add(p.with(b -> {
                b.alternative = false;
                return b;
            }));
        }
        if (p.priority() != null) {
            out.add(p.with(b -> {
                b.priority = null;
                return b;
            }));
        }
        if (p.disposer()) {
            out.add(p.with(b -> {
                b.disposer = false;
                return b;
            }));
        }
        if (p.scope() != Scope.DEPENDENT) {
            out.add(p.with(b -> {
                b.scope = Scope.DEPENDENT;
                return b;
            }));
        }
        if (p.isStatic()) {
            out.add(p.with(b -> {
                b.isStatic = false;
                return b;
            }));
        }
        return out;
    }

    private static List<ObserverDef> simplifications(ObserverDef o) {
        List<ObserverDef> out = new ArrayList<>();
        if (!o.qualifiers().isEmpty()) {
            out.add(new ObserverDef(o.name(), o.eventType(), List.of(), o.priority(), o.ifExists(), o.isStatic()));
        }
        if (o.priority() != null) {
            out.add(new ObserverDef(o.name(), o.eventType(), o.qualifiers(), null, o.ifExists(), o.isStatic()));
        }
        if (o.ifExists()) {
            out.add(new ObserverDef(o.name(), o.eventType(), o.qualifiers(), o.priority(), false, o.isStatic()));
        }
        if (o.isStatic()) {
            out.add(new ObserverDef(o.name(), o.eventType(), o.qualifiers(), o.priority(), o.ifExists(), false));
        }
        return out;
    }

    private static BeanDef set(BeanDef bean, java.util.function.Consumer<BeanDef.Builder> change) {
        return bean.with(b -> {
            change.accept(b);
            return b;
        });
    }

    private static Scenario replaceBean(Scenario s, BeanDef bean, UnaryOperator<BeanDef> change) {
        List<BeanDef> beans = new ArrayList<>(s.beans());
        beans.set(beans.indexOf(bean), change.apply(bean));
        return s.withBeans(beans);
    }

    private static <T> List<T> without(List<T> list, int index) {
        List<T> copy = new ArrayList<>(list);
        copy.remove(index);
        return copy;
    }

    private static <T> List<T> replace(List<T> list, int index, T value) {
        List<T> copy = new ArrayList<>(list);
        copy.set(index, value);
        return copy;
    }
}
