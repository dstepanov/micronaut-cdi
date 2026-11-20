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

import io.micronaut.cdi.fuzz.Fuzzer.Evaluation;
import io.micronaut.cdi.fuzz.Fuzzer.SeedResult;
import io.micronaut.cdi.fuzz.RunResult.Outcome;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A campaign: the seeds {@code fuzz.seed} to {@code fuzz.seed + fuzz.count - 1}, or the one seed
 * {@code fuzz.replay}. It runs only from the {@code fuzz} task, and it does not fail on a divergence: what it
 * found is in {@code build/fuzz}, one directory per seed that diverged, and in {@code build/fuzz/summary.md}.
 */
@Tag("campaign")
class FuzzCampaignTest {

    /**
     * The seeds of a campaign, counted apart from the seed, which may be any long: the first plus the count wraps
     * past {@link Long#MAX_VALUE}.
     */
    static List<Long> seeds(long first, int count) {
        List<Long> seeds = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            seeds.add(first + index);
        }
        return seeds;
    }

    @Test
    void campaign() {
        Fuzzer fuzzer = new Fuzzer(FuzzConfig.reportDir(), FuzzConfig.size());
        Long replay = FuzzConfig.replay();
        long first = replay != null ? replay : FuzzConfig.seed();
        int count = replay != null ? 1 : FuzzConfig.count();
        Map<String, Integer> tally = new TreeMap<>();
        fuzzer.appendSummary("\n## Campaign from seed " + Long.toUnsignedString(first) + ", " + count + " scenarios, "
            + FuzzConfig.size() + "\n\n| seed | severity | divergence | known | size | shrunk to | report |\n"
            + "| --- | --- | --- | --- | --- | --- | --- |");
        try {
            for (long seed : seeds(first, count)) {
                long started = System.nanoTime();
                SeedResult result = fuzzer.runSeed(seed, FuzzConfig.shrink(), FuzzConfig.shrinkBudget(),
                    FuzzConfig.keepAll());
                Evaluation e = result.original();
                String key;
                if (e.harnessError()) {
                    key = "harness error";
                } else if (e.divergence() == null) {
                    key = e.weld().outcome() == Outcome.DEPLOYED ? "agree: deployed" : "agree: refused";
                } else {
                    key = e.divergence().severity() + " " + e.divergence().category()
                        + (e.known().isPresent() ? " (known)" : "");
                }
                tally.merge(key, 1, Integer::sum);
                long millis = (System.nanoTime() - started) / 1_000_000;
                System.out.printf("seed %d: %s [%d ms]%s%n", seed, key, millis,
                    result.report() == null ? "" : " -> " + result.report());
                if (e.divergence() != null || e.harnessError()) {
                    String shrunkSize = result.shrunk() == null ? "" : String.valueOf(result.shrunk().scenario().size());
                    fuzzer.appendSummary("| " + seed + " | "
                        + (e.harnessError() ? "HARNESS" : e.divergence().severity()) + " | "
                        + (e.harnessError() ? "the scenario does not compile" : e.divergence().signature() + ": "
                        + e.divergence().summary()) + " | "
                        + e.known().map(KnownDifferences.Rule::conformance).orElse("") + " | "
                        + e.scenario().size() + " | " + shrunkSize + " | " + result.report() + " |");
                }
            }
        } finally {
            fuzzer.close();
            StringBuilder totals = new StringBuilder("\nTotals:");
            tally.forEach((k, v) -> totals.append("\n- ").append(k).append(": ").append(v));
            System.out.println(totals);
            fuzzer.appendSummary(totals.toString());
        }
    }
}
