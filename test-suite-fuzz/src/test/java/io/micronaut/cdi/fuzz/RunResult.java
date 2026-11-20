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

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;

/**
 * What one implementation did with a scenario.
 *
 * @param outcome   Whether it deployed, and if not how it refused
 * @param atCompile Whether the refusal came from the compiler, which for micronaut-cdi is part of deployment
 * @param detail    What the failure said, for the report
 * @param trace     What the driver logged, where the scenario deployed
 */
record RunResult(Outcome outcome, boolean atCompile, String detail, List<String> trace) {

    RunResult {
        trace = List.copyOf(trace);
    }

    enum Outcome {
        DEPLOYED,
        DEFINITION_ERROR,
        DEPLOYMENT_ERROR,
        /**
         * The container refused to start with something that is neither of the specification's two exceptions.
         */
        OTHER_ERROR,
        /**
         * The driver itself failed, past every probe's own handling: a stack overflow, an error.
         */
        CRASH,
        TIMEOUT,
        /**
         * The scenario is not valid Java: a bug of the generator, not of either implementation.
         */
        HARNESS_ERROR;

        boolean isRefusal() {
            return this == DEFINITION_ERROR || this == DEPLOYMENT_ERROR || this == OTHER_ERROR;
        }
    }

    static RunResult deployed(List<String> trace) {
        return deployed(trace, "");
    }

    /**
     * A deployment that ran its probes.
     *
     * @param trace  What they logged
     * @param caught The stack traces of what they caught
     */
    static RunResult deployed(List<String> trace, String caught) {
        return new RunResult(Outcome.DEPLOYED, false, caught, trace);
    }

    static RunResult of(Outcome outcome, boolean atCompile, String detail) {
        return new RunResult(outcome, atCompile, detail, List.of());
    }

    /**
     * Classifies what a container's start threw by the specification's exceptions anywhere in its cause chain.
     */
    static RunResult refused(Throwable failure) {
        Outcome outcome = Outcome.OTHER_ERROR;
        for (Throwable t = failure; t != null; t = t.getCause()) {
            for (Class<?> type = t.getClass(); type != null; type = type.getSuperclass()) {
                if (type.getName().equals("jakarta.enterprise.inject.spi.DefinitionException")) {
                    outcome = Outcome.DEFINITION_ERROR;
                } else if (type.getName().equals("jakarta.enterprise.inject.spi.DeploymentException")) {
                    outcome = Outcome.DEPLOYMENT_ERROR;
                }
            }
            if (outcome != Outcome.OTHER_ERROR) {
                break;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return of(outcome, false, stackTrace(failure));
    }

    /**
     * Classifies what the compiler with micronaut-cdi's processors reported, as the kit's adapter does: a bean that
     * cannot be proxied, and what an extension reported as a deployment problem, are deployment problems; anything
     * else is a definition error.
     */
    static RunResult refusedByCompiler(List<String> errors) {
        boolean deployment = false;
        for (String message : errors) {
            if (message.contains("Cannot apply AOP advice")
                || message.contains("inherits AOP advice but is declared final")
                || message.contains("cannot be proxied")
                || message.contains("must not be final unless it is private or static")
                || message.contains("jakarta.enterprise.inject.spi.DeploymentException")
                || message.contains("Unsatisfied dependency")
                || message.contains("Ambiguous dependency")) {
                deployment = true;
            }
        }
        return of(deployment ? Outcome.DEPLOYMENT_ERROR : Outcome.DEFINITION_ERROR, true, String.join("\n", errors));
    }

    static String stackTrace(Throwable failure) {
        StringWriter out = new StringWriter();
        failure.printStackTrace(new PrintWriter(out));
        String text = out.toString();
        return text.length() > 20_000 ? text.substring(0, 20_000) + "\n..." : text;
    }

    String summary() {
        return outcome + (atCompile ? " (at compile)" : "");
    }
}
