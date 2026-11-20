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

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Compares what the two implementations did with one scenario.
 *
 * <p>A trace is compared twice. Its normalized form leaves out what the specification leaves open: the order of a
 * stretch the driver marked unordered (what an {@code Instance} iterates over, what the container destroys as it
 * shuts down), and the order of the observers of one priority. A difference there is {@link Severity#HARD}. A
 * difference only in the exact order is {@link Severity#SOFT}: often legitimate, sometimes an ordering bug, and
 * reported apart.</p>
 */
final class TraceComparison {

    private static final Pattern PRIORITY = Pattern.compile("^p=(-?\\d+) ");

    private TraceComparison() {
    }

    enum Severity {
        HARD, SOFT
    }

    enum Category {
        /**
         * One deployed and the other refused.
         */
        DEPLOYMENT,
        /**
         * Both refused, one with a definition error and the other with a deployment problem, or otherwise.
         */
        REFUSAL_KIND,
        /**
         * Both deployed and the probes observed different things.
         */
        TRACE,
        /**
         * Both deployed and observed the same things in a different order.
         */
        ORDER,
        /**
         * One side crashed or timed out.
         */
        CRASH
    }

    /**
     * A difference between the two implementations.
     *
     * @param severity  How much it matters
     * @param category  What kind of difference it is
     * @param signature What identifies it while the scenario shrinks: the category, and the probe or side involved
     * @param summary   A line for the report
     */
    record Divergence(Severity severity, Category category, String signature, String summary) {
    }

    /**
     * The difference between the two runs, or {@code null} where they agree.
     */
    static Divergence compare(RunResult micronaut, RunResult weld) {
        Outcome m = micronaut.outcome();
        Outcome w = weld.outcome();
        if (m == Outcome.CRASH || m == Outcome.TIMEOUT || w == Outcome.CRASH || w == Outcome.TIMEOUT) {
            if (m == w) {
                return null;
            }
            String side = (m == Outcome.CRASH || m == Outcome.TIMEOUT) ? "micronaut " + m : "weld " + w;
            return new Divergence(Severity.HARD, Category.CRASH, "CRASH:" + side, side);
        }
        if (m == Outcome.DEPLOYED && w == Outcome.DEPLOYED) {
            List<String> mt = normalize(micronaut.trace());
            List<String> wt = normalize(weld.trace());
            if (!mt.equals(wt)) {
                String probe = firstDifference(mt, wt);
                return new Divergence(Severity.HARD, Category.TRACE, "TRACE:" + probe,
                    "the traces differ from probe " + probe);
            }
            if (!micronaut.trace().equals(weld.trace())) {
                String probe = firstDifference(micronaut.trace(), weld.trace());
                return new Divergence(Severity.SOFT, Category.ORDER, "ORDER:" + probe,
                    "the traces are in a different order from probe " + probe);
            }
            return null;
        }
        if (m == Outcome.DEPLOYED || w == Outcome.DEPLOYED) {
            String who = m == Outcome.DEPLOYED ? "weld refuses (" + w + "), micronaut deploys"
                : "micronaut refuses (" + micronaut.summary() + "), weld deploys";
            String signature = m == Outcome.DEPLOYED ? "DEPLOYMENT:weld-refuses" : "DEPLOYMENT:micronaut-refuses";
            return new Divergence(Severity.HARD, Category.DEPLOYMENT, signature, who);
        }
        if (m != w) {
            return new Divergence(Severity.SOFT, Category.REFUSAL_KIND, "REFUSAL_KIND:" + m + "/" + w,
                "micronaut " + micronaut.summary() + ", weld " + w);
        }
        return null;
    }

    /**
     * The trace with the order the specification leaves open taken out of it: an unordered stretch sorted with the
     * sequence of observer priorities it notified in kept, and the
     * notification of an event reduced to the lines it logged, sorted, and the sequence of observer priorities it
     * notified in, sorted - so that observers notified out of the order of their priorities still differ. What
     * the container did as it started, before the first probe, is a notification of its lifecycle events.
     */
    static List<String> normalize(List<String> trace) {
        List<String> out = new ArrayList<>();
        int i = 0;
        int firstProbe = 0;
        while (firstProbe < trace.size() && !trace.get(firstProbe).startsWith("#")) {
            firstProbe++;
        }
        if (firstProbe > 0) {
            out.addAll(notification(trace.subList(0, firstProbe)));
            i = firstProbe;
        }
        while (i < trace.size()) {
            String line = trace.get(i);
            if (line.equals("{") || line.equals("[")) {
                String close = line.equals("{") ? "}" : "]";
                int end = i + 1;
                while (end < trace.size() && !trace.get(end).equals(close)) {
                    end++;
                }
                List<String> stretch = trace.subList(i + 1, Math.min(end, trace.size()));
                out.add(line);
                // an unordered stretch is sorted too, and the observers it notified - of Shutdown, or of the end of a
                // context, as the container shuts down - are still notified in the order of their priorities
                out.addAll(notification(stretch));
                out.add(close);
                i = end + 1;
            } else {
                out.add(line);
                i++;
            }
        }
        return out;
    }

    private static List<String> notification(List<String> lines) {
        List<String> out = new ArrayList<>(lines.stream().sorted().toList());
        List<Integer> priorities = new ArrayList<>();
        for (String line : lines) {
            Matcher matcher = PRIORITY.matcher(line);
            if (matcher.find()) {
                priorities.add(Integer.parseInt(matcher.group(1)));
            }
        }
        boolean ascending = true;
        for (int i = 1; i < priorities.size(); i++) {
            if (priorities.get(i) < priorities.get(i - 1)) {
                ascending = false;
                break;
            }
        }
        out.add(ascending ? "(observers in order of priority)" : "(observers out of order: " + priorities + ")");
        return out;
    }

    /**
     * The probe the first differing line belongs to.
     */
    static String firstDifference(List<String> a, List<String> b) {
        String probe = "startup";
        int n = Math.min(a.size(), b.size());
        for (int i = 0; i < n; i++) {
            if (a.get(i).startsWith("#")) {
                probe = a.get(i).substring(1);
            }
            if (!a.get(i).equals(b.get(i))) {
                return probe;
            }
        }
        if (n < a.size() && a.get(n).startsWith("#")) {
            return a.get(n).substring(1);
        }
        return probe;
    }
}
