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

import io.micronaut.cdi.fuzz.TraceComparison.Category;
import io.micronaut.cdi.fuzz.TraceComparison.Divergence;

import java.util.List;
import java.util.Optional;

/**
 * The differences CONFORMANCE.md records as intended, recognized so that a campaign tags them rather than reporting
 * them as findings. Each rule names the heading of CONFORMANCE.md it stands for; a rule is added only with that
 * heading, and only for what the heading says.
 */
final class KnownDifferences {

    /**
     * A recorded difference.
     *
     * @param conformance The heading of CONFORMANCE.md that records it
     * @param matcher     Whether a divergence is an instance of it
     */
    record Rule(String conformance, Matcher matcher) {
    }

    @FunctionalInterface
    interface Matcher {
        boolean matches(Scenario scenario, Divergence divergence, RunResult micronaut, RunResult weld);
    }

    static final List<Rule> RULES = List.of(
        // a deployment both refuse, micronaut-cdi as it compiles: which of the two exceptions the compiler's
        // refusal amounts to is read off its diagnostics, and the specification's line between a definition error
        // and a deployment problem is not one the compiler draws
        new Rule("An unproxyable normal scoped bean is detected as it is compiled; "
            + "What takes the whole deployment is validated as the container starts",
            (scenario, divergence, micronaut, weld) -> divergence.category() == Category.REFUSAL_KIND
                && micronaut.atCompile()),
        // a chain of dependent beans that leads back to its start, with no bean in a normal scope on it: Weld
        // refuses it as it deploys (WELD-001443), micronaut-cdi deploys it and fails as it creates a bean of it.
        // Section 5.4 requires a container to support only the circularities a normal scoped bean breaks.
        // Resolving one is a stack overflow on Weld and an exception on micronaut-cdi
        new Rule("A cycle of pseudo-scoped beans fails as it is created",
            (scenario, divergence, micronaut, weld) -> divergence.category() == Category.DEPLOYMENT
                && weld.detail().contains("WELD-001443") && !weld.detail().contains("Exception List")
                || divergence.category() == Category.TRACE
                && weld.trace().stream().anyMatch(line -> line.contains("java.lang.StackOverflowError"))),
        // an application scoped bean declaring a producer of a bean of another scope: micronaut-cdi destroys its
        // contextual instance after each product is created, which Weld does only as the container shuts down
        new Rule("Known limitations a review has named: a bean in a normal scope that declares a producer of a bean "
            + "of another scope",
            (scenario, divergence, micronaut, weld) -> divergence.category() == Category.TRACE
                && destroyedByItsProducer(scenario, micronaut, weld)),
        // the order of what the specification leaves unordered: what an Instance iterates over, what is destroyed
        // as the container shuts down, the observers of one priority. A difference only there is no finding
        new Rule("Order the specification leaves open: Instance iteration, destruction at shutdown, observers of one priority",
            (scenario, divergence, micronaut, weld) -> divergence.category() == Category.ORDER)
    );

    private KnownDifferences() {
    }

    /**
     * Whether micronaut-cdi destroyed, before the container shut down, an application scoped bean that declares a
     * producer of another scope, and Weld did not.
     */
    static boolean destroyedByItsProducer(Scenario scenario, RunResult micronaut, RunResult weld) {
        for (Scenario.BeanDef bean : scenario.beans()) {
            if (bean.scope() != Scenario.Scope.APPLICATION || !bean.preDestroy()) {
                continue;
            }
            boolean producesAnotherScope = bean.producers().stream()
                .anyMatch(p -> !p.isStatic() && p.scope() != bean.scope());
            String destroyed = bean.name() + ".preDestroy@";
            if (producesAnotherScope && beforeShutdown(micronaut.trace()).stream().anyMatch(l -> l.startsWith(destroyed))
                && beforeShutdown(weld.trace()).stream().noneMatch(l -> l.startsWith(destroyed))) {
                return true;
            }
        }
        return false;
    }

    private static List<String> beforeShutdown(List<String> trace) {
        int shutdown = trace.indexOf("#shutdown");
        return shutdown < 0 ? trace : trace.subList(0, shutdown);
    }

    static Optional<Rule> match(Scenario scenario, Divergence divergence, RunResult micronaut, RunResult weld) {
        return RULES.stream().filter(r -> r.matcher().matches(scenario, divergence, micronaut, weld)).findFirst();
    }
}
