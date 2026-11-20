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

import io.micronaut.cdi.fuzz.KnownDifferences.Rule;
import io.micronaut.cdi.fuzz.RunResult.Outcome;
import io.micronaut.cdi.fuzz.ScenarioRenderer.Rendered;
import io.micronaut.cdi.fuzz.TraceComparison.Divergence;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Generates, runs, compares, shrinks and reports.
 */
final class Fuzzer {

    private static final Pattern WELD_CODE = Pattern.compile("WELD-\\d+");

    private final Path reportDir;
    private final Path workRoot;
    private final ScenarioGenerator.Size size;
    private int variants;

    Fuzzer(Path reportDir, ScenarioGenerator.Size size) {
        this.reportDir = reportDir;
        this.size = size;
        try {
            Files.createDirectories(reportDir);
            this.workRoot = Files.createTempDirectory("cdi-fuzz");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * One scenario run on both implementations.
     *
     * @param scenario   The scenario
     * @param rendered   Its sources
     * @param micronaut  What micronaut-cdi did, {@code null} where the scenario did not compile for Weld
     * @param weld       What Weld did
     * @param divergence How they differ, or {@code null}
     * @param known      The recorded difference the divergence is, if it is one
     */
    record Evaluation(Scenario scenario, Rendered rendered, RunResult micronaut, RunResult weld,
                      Divergence divergence, Optional<Rule> known) {

        boolean harnessError() {
            return weld.outcome() == Outcome.HARNESS_ERROR
                || (micronaut != null && micronaut.outcome() == Outcome.HARNESS_ERROR);
        }
    }

    /**
     * What a seed came to.
     *
     * @param seed     The seed
     * @param original The generated scenario's evaluation
     * @param shrunk   The shrunk scenario's evaluation, where it was shrunk
     * @param report   The directory the report was written to, where one was
     */
    record SeedResult(long seed, Evaluation original, Evaluation shrunk, Path report) {
    }

    Evaluation evaluate(Scenario scenario) {
        String packageName = "fuzz.s" + Long.toUnsignedString(scenario.seed()) + ".v" + (variants++);
        Rendered rendered = ScenarioRenderer.render(scenario, packageName);
        Path work;
        try {
            work = Files.createTempDirectory(workRoot, "run");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        try {
            RunResult weld = Runners.runWeld(rendered, work);
            if (weld.outcome() == Outcome.HARNESS_ERROR) {
                return new Evaluation(scenario, rendered, null, weld, null, Optional.empty());
            }
            RunResult micronaut = Runners.runMicronaut(rendered, work);
            if (micronaut.outcome() == Outcome.HARNESS_ERROR) {
                // a processor crashed: nothing to compare
                return new Evaluation(scenario, rendered, micronaut, weld, null, Optional.empty());
            }
            Divergence divergence = TraceComparison.compare(micronaut, weld);
            Optional<Rule> known = divergence == null ? Optional.empty()
                : KnownDifferences.match(scenario, divergence, micronaut, weld);
            return new Evaluation(scenario, rendered, micronaut, weld, divergence, known);
        } finally {
            delete(work);
        }
    }

    SeedResult runSeed(long seed, boolean shrink, int shrinkBudget, boolean keepAll) {
        Scenario scenario = ScenarioGenerator.generate(seed, size);
        Evaluation original = evaluate(scenario);
        if (original.divergence() == null && !original.harnessError()) {
            Path report = keepAll ? writeReport(seed, original, null, 0) : null;
            return new SeedResult(seed, original, null, report);
        }
        Evaluation shrunk = null;
        int runs = 0;
        if (shrink && (original.divergence() != null && original.known().isEmpty() || processorCrash(original) != null)) {
            Shrinker shrinker = new Shrinker(candidate -> original.divergence() != null
                ? reproduces(original, evaluate(candidate))
                : processorCrash(original).equals(processorCrash(evaluate(candidate))), shrinkBudget);
            Scenario reduced = shrinker.shrink(scenario);
            runs = shrinker.runs();
            shrunk = evaluate(reduced);
        }
        return new SeedResult(seed, original, shrunk, writeReport(seed, original, shrunk, runs));
    }

    /**
     * Whether a shrunk scenario still shows the divergence of the original: the same signature, the same outcomes - a
     * side that refuses for another reason, such as Weld refusing an empty deployment, is another divergence - and
     * Weld refusing for the same problems, so that a candidate that took out the problem micronaut-cdi missed,
     * leaving another that Weld reports with the same outcome, is not taken for it; nor is a candidate whose
     * divergence is a known difference, which a reduction - a scope simplified - can turn a finding into.
     */
    static boolean reproduces(Evaluation original, Evaluation candidate) {
        return candidate.divergence() != null
            && candidate.known().isEmpty()
            && candidate.divergence().signature().equals(original.divergence().signature())
            && candidate.micronaut().outcome() == original.micronaut().outcome()
            && candidate.weld().outcome() == original.weld().outcome()
            && weldProblems(candidate.weld()).equals(weldProblems(original.weld()));
    }

    /**
     * The first line of what a processor threw compiling a scenario for micronaut-cdi, or {@code null} where none
     * threw: a crash is a finding of its own, shrunk to the scenario that still throws the same.
     */
    static String processorCrash(Evaluation e) {
        if (e.micronaut() == null || e.micronaut().outcome() != Outcome.HARNESS_ERROR || !e.micronaut().atCompile()) {
            return null;
        }
        return e.micronaut().detail().lines().findFirst().orElse("");
    }

    /**
     * The codes of the problems Weld reported refusing a deployment, such as {@code WELD-001408}.
     */
    static Set<String> weldProblems(RunResult weld) {
        Set<String> codes = new TreeSet<>();
        if (weld.outcome().isRefusal() && weld.detail() != null) {
            Matcher matcher = WELD_CODE.matcher(weld.detail());
            while (matcher.find()) {
                codes.add(matcher.group());
            }
        }
        return codes;
    }

    private Path writeReport(long seed, Evaluation original, Evaluation shrunk, int shrinkRuns) {
        Path dir = reportDir.resolve("seed-" + Long.toUnsignedString(seed));
        delete(dir);
        StringBuilder summary = new StringBuilder();
        summary.append("seed: ").append(Long.toUnsignedString(seed)).append('\n');
        summary.append("size: ").append(size).append('\n');
        describe(summary, "original", original);
        if (shrunk != null) {
            summary.append("shrink runs: ").append(shrinkRuns).append('\n');
            describe(summary, "shrunk", shrunk);
        }
        write(dir.resolve("summary.txt"), summary.toString());
        writeEvaluation(dir.resolve("original"), original);
        if (shrunk != null) {
            writeEvaluation(dir.resolve("shrunk"), shrunk);
        }
        return dir;
    }

    private static void describe(StringBuilder out, String label, Evaluation e) {
        out.append(label).append(" scenario size: ").append(e.scenario().size()).append('\n');
        out.append(label).append(" weld: ").append(e.weld().summary()).append('\n');
        if (e.micronaut() != null) {
            out.append(label).append(" micronaut: ").append(e.micronaut().summary()).append('\n');
        }
        if (e.divergence() != null) {
            out.append(label).append(" divergence: ").append(e.divergence().severity()).append(' ')
                .append(e.divergence().signature()).append(" - ").append(e.divergence().summary()).append('\n');
        }
        e.known().ifPresent(rule -> out.append(label).append(" known: ").append(rule.conformance()).append('\n'));
    }

    private static void writeEvaluation(Path dir, Evaluation e) {
        for (Map.Entry<String, String> source : e.rendered().sources().entrySet()) {
            write(dir.resolve("src").resolve(source.getKey().replace('.', '/') + ".java"), source.getValue());
        }
        write(dir.resolve("scenario.txt"), e.scenario().toString().replace("], ", "],\n  "));
        write(dir.resolve("weld.trace"), String.join("\n", e.weld().trace()) + "\n");
        write(dir.resolve("weld.detail"), e.weld().detail());
        if (e.micronaut() != null) {
            write(dir.resolve("micronaut.trace"), String.join("\n", e.micronaut().trace()) + "\n");
            write(dir.resolve("micronaut.detail"), e.micronaut().detail());
            write(dir.resolve("diff.txt"), sideBySide(TraceComparison.normalize(e.micronaut().trace()),
                TraceComparison.normalize(e.weld().trace())));
        }
    }

    /**
     * The normalized traces side by side, micronaut-cdi on the left, a line that differs marked.
     */
    static String sideBySide(List<String> micronaut, List<String> weld) {
        StringBuilder out = new StringBuilder(String.format("  %-60s | %s%n", "micronaut-cdi", "weld"));
        int n = Math.max(micronaut.size(), weld.size());
        for (int i = 0; i < n; i++) {
            String m = i < micronaut.size() ? micronaut.get(i) : "";
            String w = i < weld.size() ? weld.get(i) : "";
            out.append(m.equals(w) ? "  " : "! ").append(String.format("%-60s | %s%n", m, w));
        }
        return out.toString();
    }

    /**
     * Appends a line to the campaign's summary.
     */
    void appendSummary(String line) {
        try {
            Files.writeString(reportDir.resolve("summary.md"), line + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    Path reportDir() {
        return reportDir;
    }

    void close() {
        delete(workRoot);
    }

    private static void write(Path file, String content) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static void delete(Path root) {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> tree = Files.walk(root)) {
            List<Path> paths = new ArrayList<>(tree.toList());
            paths.sort(Comparator.reverseOrder());
            for (Path path : paths) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            // a temporary directory left behind
        }
    }
}
