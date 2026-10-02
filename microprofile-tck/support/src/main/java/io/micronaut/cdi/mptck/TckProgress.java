/* Licensed under the Apache License, Version 2.0. */
package io.micronaut.cdi.mptck;

import org.testng.*;
import java.nio.file.*;

/** Durable per-invocation progress, including setup callbacks, for long upstream suites. */
public final class TckProgress implements IInvokedMethodListener {
    @Override public void beforeInvocation(IInvokedMethod method, ITestResult result) { record("START", method, result); }
    @Override public void afterInvocation(IInvokedMethod method, ITestResult result) { record(Integer.toString(result.getStatus()), method, result); }
    private static synchronized void record(String status, IInvokedMethod method, ITestResult result) {
        String configured = System.getProperty("mp.tck.evidence");
        if (configured == null) return;
        try {
            Path directory = Path.of(configured);
            Files.createDirectories(directory);
            String line = java.time.Instant.now() + "\t" + status + "\t" + (method.isTestMethod() ? "TEST" : "CONFIG")
                + "\t" + result.getTestClass().getName() + "\t" + method.getTestMethod().getMethodName() + "\n";
            Files.writeString(directory.resolve("events.tsv"), line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
    }
}
