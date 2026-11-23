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

import io.micronaut.cdi.fuzz.RunResult.Outcome;
import io.micronaut.cdi.fuzz.Scenario.BeanDef;
import io.micronaut.cdi.fuzz.Scenario.ObserverDef;
import io.micronaut.cdi.fuzz.Scenario.Probe;
import io.micronaut.cdi.fuzz.Scenario.ProbeKind;
import io.micronaut.cdi.fuzz.Scenario.Scope;
import io.micronaut.cdi.fuzz.TraceComparison.Category;
import io.micronaut.cdi.fuzz.TraceComparison.Divergence;
import io.micronaut.cdi.fuzz.TraceComparison.Severity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The fuzzer's own tests, which run with {@code check}: the generator is reproducible and generates valid Java, the
 * comparison leaves out what the specification leaves open and nothing else, the shrinker reduces, and a scenario
 * both implementations agree on is reported as agreeing.
 */
class HarnessTest {

    @Test
    void aSeedAlwaysGeneratesTheSameScenario() {
        for (long seed = 0; seed < 50; seed++) {
            assertEquals(ScenarioGenerator.generate(seed, ScenarioGenerator.Size.MEDIUM),
                ScenarioGenerator.generate(seed, ScenarioGenerator.Size.MEDIUM));
        }
        assertNotEquals(ScenarioGenerator.generate(1, ScenarioGenerator.Size.MEDIUM),
            ScenarioGenerator.generate(2, ScenarioGenerator.Size.MEDIUM));
    }

    @Test
    void generatedScenariosAreValidJava(@TempDir Path dir) {
        List<String> failures = new ArrayList<>();
        for (long seed = 0; seed < 30; seed++) {
            Scenario scenario = ScenarioGenerator.generate(seed, ScenarioGenerator.Size.LARGE);
            ScenarioRenderer.Rendered rendered = ScenarioRenderer.render(scenario, "fuzz.valid.s" + seed);
            ScenarioCompiler.Result result = ScenarioCompiler.compileForWeld(rendered.sources(), dir.resolve("s" + seed));
            if (!result.success()) {
                failures.add("seed " + seed + ": " + result.errors());
            }
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }

    @Test
    void theOrderOfAnUnorderedStretchIsLeftOut() {
        RunResult a = RunResult.deployed(List.of("#X1", "{", "each=B1", "each=B2", "}"));
        RunResult b = RunResult.deployed(List.of("#X1", "{", "each=B2", "each=B1", "}"));
        Divergence divergence = TraceComparison.compare(a, b);
        assertEquals(Severity.SOFT, divergence.severity());
        assertEquals(Category.ORDER, divergence.category());
    }

    @Test
    void observersOfOnePriorityMayBeNotifiedInEitherOrder() {
        RunResult a = RunResult.deployed(List.of("#X1", "[", "p=5 O1@B1 EvA", "p=5 O2@B2 EvA", "p=10 O3@B1 EvA", "]"));
        RunResult b = RunResult.deployed(List.of("#X1", "[", "p=5 O2@B2 EvA", "p=5 O1@B1 EvA", "p=10 O3@B1 EvA", "]"));
        assertEquals(Severity.SOFT, TraceComparison.compare(a, b).severity());
    }

    @Test
    void observersNotifiedOutOfTheOrderOfTheirPrioritiesDiffer() {
        RunResult a = RunResult.deployed(List.of("#X1", "[", "p=10 O3@B1 EvA", "p=5 O1@B1 EvA", "]"));
        RunResult b = RunResult.deployed(List.of("#X1", "[", "p=5 O1@B1 EvA", "p=10 O3@B1 EvA", "]"));
        Divergence divergence = TraceComparison.compare(a, b);
        assertEquals(Severity.HARD, divergence.severity());
        assertEquals("TRACE:X1", divergence.signature());
    }

    @Test
    void observersNotifiedOutOfTheOrderOfTheirPrioritiesAsTheContainerShutsDownDiffer() {
        RunResult a = RunResult.deployed(List.of("#shutdown", "{", "destroy B1", "p=2500 O3@B1 Shutdown",
            "p=5 O1@B2 Shutdown", "}"));
        RunResult b = RunResult.deployed(List.of("#shutdown", "{", "p=5 O1@B2 Shutdown", "p=2500 O3@B1 Shutdown",
            "destroy B1", "}"));
        Divergence divergence = TraceComparison.compare(a, b);
        assertEquals(Severity.HARD, divergence.severity());
        assertEquals(Category.TRACE, divergence.category());
    }

    @Test
    void noTwoInterceptorsShareAPriorityAndAConditionalObserverIsNotifiedLast() {
        for (long seed = 1; seed <= 2000; seed++) {
            Scenario scenario = ScenarioGenerator.generate(seed, ScenarioGenerator.Size.LARGE);
            List<Integer> priorities = scenario.interceptors().stream().map(i -> i.priority())
                .filter(java.util.Objects::nonNull).toList();
            assertEquals(priorities.size(), new java.util.HashSet<>(priorities).size(), "seed " + seed);
            for (BeanDef bean : scenario.beans()) {
                for (ObserverDef observer : bean.observers()) {
                    if (observer.ifExists()) {
                        assertTrue(observer.priority() != null && observer.priority() > 2500, "seed " + seed);
                    }
                }
            }
        }
    }

    @Test
    void whatAStreamOfAnInstanceCreatesIsAnUnorderedStretch() {
        String log = ScenarioRenderer.render(ScenarioGenerator.generate(1, ScenarioGenerator.Size.SMALL), "fuzz.log")
            .sources().values().stream().filter(source -> source.contains("class Log")).findFirst().orElseThrow();
        assertTrue(log.contains("unorderedCall(() -> String.valueOf(instance.stream()"), log);
    }

    @Test
    void aProcessorThatCrashedGivesNoVerdict() {
        assertEquals(Outcome.HARNESS_ERROR, Runners.refusedByCompiler(new ScenarioCompiler.Result(false,
            List.of("the compiler threw java.lang.RuntimeException: java.lang.NoClassDefFoundError: X"), true)).outcome());
        assertEquals(Outcome.DEFINITION_ERROR, Runners.refusedByCompiler(new ScenarioCompiler.Result(false,
            List.of("S.java:3: A method may not be annotated both Inject and Produces"), false)).outcome());
    }

    @Test
    void aShrunkScenarioReproducesOnlyWhereWeldRefusesItForTheSameProblems() {
        RunResult deployed = RunResult.deployed(List.of());
        RunResult both = RunResult.of(Outcome.DEPLOYMENT_ERROR, false,
            "WELD-001408: Unsatisfied dependencies ... WELD-001443: Pseudo scoped bean has circular dependencies");
        RunResult cycleOnly = RunResult.of(Outcome.DEPLOYMENT_ERROR, false,
            "WELD-001443: Pseudo scoped bean has circular dependencies");
        Divergence divergence = TraceComparison.compare(deployed, both);
        Fuzzer.Evaluation original = new Fuzzer.Evaluation(null, null, deployed, both, divergence,
            java.util.Optional.empty());
        assertTrue(Fuzzer.reproduces(original, original));
        assertTrue(!Fuzzer.reproduces(original, new Fuzzer.Evaluation(null, null, deployed, cycleOnly,
            TraceComparison.compare(deployed, cycleOnly), java.util.Optional.empty())));
    }

    @Test
    void whatIsLoggedOutsideAStretchIsCompared() {
        RunResult a = RunResult.deployed(List.of("#X1", "get=B1", "#X2", "get=B2"));
        RunResult b = RunResult.deployed(List.of("#X1", "get=B1", "#X2", "!jakarta.enterprise.inject.AmbiguousResolutionException"));
        assertEquals("TRACE:X2", TraceComparison.compare(a, b).signature());
        assertNull(TraceComparison.compare(a, a));
    }

    @Test
    void oneSideDeployingIsADivergence() {
        RunResult deployed = RunResult.deployed(List.of());
        RunResult refused = RunResult.of(Outcome.DEFINITION_ERROR, true, "");
        assertEquals("DEPLOYMENT:micronaut-refuses", TraceComparison.compare(refused, deployed).signature());
        assertEquals("DEPLOYMENT:weld-refuses", TraceComparison.compare(deployed, refused).signature());
        assertNull(TraceComparison.compare(refused, RunResult.of(Outcome.DEFINITION_ERROR, false, "")));
    }

    @Test
    void everyShapeOfInterceptionIsGenerated() {
        java.util.Set<String> seen = new java.util.TreeSet<>();
        for (long seed = 1; seed <= 300; seed++) {
            Scenario scenario = ScenarioGenerator.generate(seed, ScenarioGenerator.Size.LARGE);
            for (BeanDef bean : scenario.beans()) {
                if (!bean.callBindings().isEmpty()) {
                    seen.add("callBindings");
                }
                if (bean.inheritsCall() && bean.superBean() != null) {
                    seen.add("inheritsCall");
                }
            }
            for (Scenario.InterceptorDef interceptor : scenario.interceptors()) {
                if (interceptor.bindings().size() > 1) {
                    seen.add("twoBindings");
                }
                if (interceptor.bindings().stream().anyMatch(b -> b.startsWith("Bind3"))) {
                    seen.add("bindingWithMembers");
                }
                if (interceptor.priority() == null) {
                    seen.add("disabled");
                }
                if (interceptor.aroundConstruct()) {
                    seen.add("aroundConstruct");
                }
                if (interceptor.shortCircuit()) {
                    seen.add("shortCircuit");
                }
                if (interceptor.stateful()) {
                    seen.add("stateful");
                }
                if (interceptor.base()) {
                    seen.add("base");
                }
                if (interceptor.reportsBindings()) {
                    seen.add("reportsBindings");
                }
            }
            if (scenario.probes().stream().anyMatch(p -> p.kind() == ProbeKind.INVOKE)) {
                seen.add("invoke");
            }
        }
        assertEquals(new java.util.TreeSet<>(List.of("aroundConstruct", "base", "bindingWithMembers", "callBindings",
            "disabled", "inheritsCall", "invoke", "reportsBindings", "shortCircuit", "stateful", "twoBindings")), seen);
    }

    @Test
    void noDecoratorIsGenerated() {
        for (long seed = 0; seed < 100; seed++) {
            assertTrue(ScenarioGenerator.generate(seed, ScenarioGenerator.Size.LARGE).decorators().isEmpty());
        }
    }

    @Test
    void whatTheSpecificationLeavesOpenIsAKnownDifference() {
        Scenario empty = new Scenario(0, List.of(), List.of(), List.of(), List.of());
        RunResult a = RunResult.deployed(List.of("#shutdown", "{", "B1.preDestroy@B1", "B2.preDestroy@B2", "}"));
        RunResult b = RunResult.deployed(List.of("#shutdown", "{", "B2.preDestroy@B2", "B1.preDestroy@B1", "}"));
        Divergence order = TraceComparison.compare(a, b);
        assertTrue(KnownDifferences.match(empty, order, a, b).isPresent());

        RunResult deployed = RunResult.deployed(List.of());
        RunResult cycle = RunResult.of(Outcome.DEPLOYMENT_ERROR, false,
            "org.jboss.weld.exceptions.DeploymentException: WELD-001443: Pseudo scoped bean has circular dependencies.");
        Divergence divergence = TraceComparison.compare(deployed, cycle);
        assertTrue(KnownDifferences.match(empty, divergence, deployed, cycle).isPresent());

        RunResult cycles = RunResult.of(Outcome.DEPLOYMENT_ERROR, false,
            "Exception List with 2 exceptions: Exception 0 : WELD-001443: Pseudo scoped bean has circular dependencies."
                + " Exception 1 : WELD-001443: Pseudo scoped bean has circular dependencies.");
        assertTrue(KnownDifferences.match(empty, TraceComparison.compare(deployed, cycles), deployed, cycles).isPresent());

        RunResult unsatisfied = RunResult.of(Outcome.DEPLOYMENT_ERROR, false,
            "org.jboss.weld.exceptions.DeploymentException: WELD-001408: Unsatisfied dependencies");
        assertTrue(KnownDifferences.match(empty, TraceComparison.compare(deployed, unsatisfied), deployed, unsatisfied)
            .isEmpty());
    }

    @Test
    void anApplicationScopedBeanDestroyedByItsProducerIsAKnownDifference() {
        Scenario.ProducerDef product = new Scenario.ProducerDef("P1", false, false, Scope.DEPENDENT, "Gen<Integer>",
            List.of(), null, false, null, false, List.of());
        BeanDef declaring = new BeanDef("B1", Scope.APPLICATION, List.of(), null, false, List.of(), null, false, null,
            List.of(), List.of(), false, true, false, List.of(), List.of(product), List.of());
        Scenario scenario = new Scenario(56, List.of(declaring), List.of(), List.of(),
            List.of(new Probe("X5", ProbeKind.ITERATE, "Svc", List.of(), false)));
        RunResult micronaut = RunResult.deployed(List.of("#X5", "{", "B1.preDestroy@B1", "each=P1", "produce P1", "}",
            "#shutdown", "{", "}"));
        RunResult weld = RunResult.deployed(List.of("#X5", "{", "each=P1", "produce P1", "}", "#shutdown", "{",
            "B1.preDestroy@B1", "}"));
        Divergence divergence = TraceComparison.compare(micronaut, weld);
        assertTrue(KnownDifferences.match(scenario, divergence, micronaut, weld).isPresent());

        RunResult intercepted = RunResult.deployed(List.of("#X5", "{", "I1.preDestroy@B1", "each=P1", "produce P1", "}",
            "#shutdown", "{", "}"));
        RunResult weldIntercepted = RunResult.deployed(List.of("#X5", "{", "each=P1", "produce P1", "}", "#shutdown",
            "{", "I1.preDestroy@B1", "}"));
        assertTrue(KnownDifferences.match(scenario, TraceComparison.compare(intercepted, weldIntercepted), intercepted,
            weldIntercepted).isPresent());

        RunResult missing = RunResult.deployed(List.of("#X5", "{", "}", "#shutdown", "{", "B1.preDestroy@B1", "}"));
        assertTrue(KnownDifferences.match(scenario, TraceComparison.compare(missing, weld), missing, weld).isEmpty());
    }

    @Test
    void theUnannouncedRequestOfAPostConstructIsAKnownDifference() {
        Scenario empty = new Scenario(0, List.of(), List.of(), List.of(), List.of());
        RunResult micronaut = RunResult.deployed(List.of("#X3", "B1.postConstruct@B1", "get=B1", "#shutdown"));
        RunResult weld = RunResult.deployed(List.of("#X3", "p=2500 O2@B2 other", "B1.postConstruct@B1",
            "p=2500 O2@B2 other", "p=2500 O2@B2 other", "get=B1", "#shutdown"));
        assertTrue(KnownDifferences.match(empty, TraceComparison.compare(micronaut, weld), micronaut, weld).isPresent());

        RunResult failed = RunResult.deployed(List.of("#X3", "B1.postConstruct@B1", "!java.lang.ClassCastException",
            "#shutdown"));
        assertTrue(KnownDifferences.match(empty, TraceComparison.compare(failed, weld), failed, weld).isEmpty());
    }

    @Test
    void aProducerOfAnUnselectedAlternativeIsAKnownDifference() {
        Scenario.ProducerDef producer = new Scenario.ProducerDef("P3", false, false, Scope.DEPENDENT, "Gen<String>",
            List.of(), null, true, 20, false, List.of());
        BeanDef declaring = new BeanDef("B8", Scope.DEPENDENT, List.of(), null, false, List.of(), null, true, null,
            List.of(), List.of(), false, false, false, List.of(), List.of(producer), List.of());
        Scenario scenario = new Scenario(313, List.of(declaring), List.of(), List.of(),
            List.of(new Probe("X13", ProbeKind.SELECT, "Gen<String>", List.of(), false)));
        RunResult micronaut = RunResult.deployed(List.of("#X13", "unsatisfied=true ambiguous=false",
            "!jakarta.enterprise.inject.UnsatisfiedResolutionException", "#shutdown"));
        RunResult weld = RunResult.deployed(List.of("#X13", "unsatisfied=false ambiguous=false", "produce P3", "get=P3",
            "#shutdown"));
        assertTrue(KnownDifferences.match(scenario, TraceComparison.compare(micronaut, weld), micronaut, weld)
            .isPresent());
        assertTrue(KnownDifferences.match(scenario, TraceComparison.compare(weld, micronaut), weld, micronaut)
            .isEmpty());
    }

    @Test
    void theShrinkerKeepsOnlyWhatReproduces() {
        Scenario scenario = ScenarioGenerator.generate(7, ScenarioGenerator.Size.LARGE);
        BeanDef culprit = new BeanDef("Culprit", Scope.APPLICATION, List.of("S1"), null, false, List.of(), null, true,
            10, List.of(), List.of(), true, true, false, List.of(), List.of(),
            List.of(new ObserverDef("O99", "EvA", List.of(), 5, false, false)));
        List<BeanDef> beans = new ArrayList<>(scenario.beans());
        beans.add(culprit);
        List<Probe> probes = new ArrayList<>(scenario.probes());
        probes.add(new Probe("X99", ProbeKind.SELECT, "S1", List.of(), false));
        Scenario seeded = scenario.withBeans(beans).withProbes(probes);
        // a "bug" that needs the alternative bean Culprit and the probe X99
        Shrinker shrinker = new Shrinker(s -> s.bean("Culprit") != null && s.bean("Culprit").alternative()
            && s.probes().stream().anyMatch(p -> p.name().equals("X99")), 10_000);
        Scenario shrunk = shrinker.shrink(seeded);
        assertEquals(1, shrunk.beans().size());
        assertEquals(1, shrunk.probes().size());
        assertTrue(shrunk.interceptors().isEmpty());
        assertTrue(shrunk.decorators().isEmpty());
        BeanDef kept = shrunk.bean("Culprit");
        assertTrue(kept.observers().isEmpty());
        assertEquals(Scope.DEPENDENT, kept.scope());
        assertNull(kept.priority());
    }

    @Test
    void weldKeepsNothingOfADeploymentItRefused(@TempDir Path dir) throws Exception {
        BeanDef unsatisfied = new BeanDef("B1", Scope.DEPENDENT, List.of("S1"), null, false, List.of(), null, false,
            null, List.of(), List.of(), false, false, false,
            List.of(new Scenario.InjectionPointDef("ip1", Scenario.IpKind.FIELD, "S3", List.of())), List.of(), List.of());
        Scenario scenario = new Scenario(0, List.of(unsatisfied), List.of(), List.of(), List.of(
            new Probe("X1", ProbeKind.SELECT, "S1", List.of(), false)));
        ScenarioRenderer.Rendered rendered = ScenarioRenderer.render(scenario, "fuzz.refused");
        RunResult result = Runners.runWeld(rendered, dir);
        assertEquals(Outcome.DEPLOYMENT_ERROR, result.outcome(), result.detail());
        Class<?> container = Runners.weldLoader().loadClass("org.jboss.weld.Container");
        assertEquals(false, container.getMethod("isSet", String.class)
            .invoke(null, Runners.weldContainerId(rendered)));
    }

    @Test
    void aScenarioBothImplementationsAgreeOnAgrees(@TempDir Path dir) {
        BeanDef service = new BeanDef("B1", Scope.APPLICATION, List.of("S1"), null, false, List.of(), null, false, null,
            List.of(), List.of(), true, true, false, List.of(), List.of(),
            List.of(new ObserverDef("O1", "EvA", List.of(), 5, false, false)));
        Scenario scenario = new Scenario(0, List.of(service), List.of(), List.of(), List.of(
            new Probe("X1", ProbeKind.SELECT, "S1", List.of(), false),
            new Probe("X2", ProbeKind.FIRE, "EvA", List.of(), false),
            new Probe("X3", ProbeKind.SELECT, "S3", List.of(), false)));
        Fuzzer fuzzer = new Fuzzer(dir.resolve("report"), ScenarioGenerator.Size.SMALL);
        try {
            Fuzzer.Evaluation e = fuzzer.evaluate(scenario);
            assertEquals(Outcome.DEPLOYED, e.weld().outcome(), e.weld().detail());
            assertEquals(Outcome.DEPLOYED, e.micronaut().outcome(), e.micronaut().detail());
            assertTrue(e.weld().trace().contains("get=B1"), e.weld().trace().toString());
            assertTrue(e.weld().trace().contains("!jakarta.enterprise.inject.UnsatisfiedResolutionException"),
                e.weld().trace().toString());
            assertNull(e.divergence(), () -> Fuzzer.sideBySide(e.micronaut().trace(), e.weld().trace()));
        } finally {
            fuzzer.close();
        }
    }

    @Test
    void theLastSeedThereIsIsReplayed() {
        assertEquals(List.of(Long.MAX_VALUE), FuzzCampaignTest.seeds(Long.MAX_VALUE, 1));
        assertEquals(List.of(Long.MAX_VALUE - 1, Long.MAX_VALUE, Long.MIN_VALUE),
            FuzzCampaignTest.seeds(Long.MAX_VALUE - 1, 3));
    }
}
