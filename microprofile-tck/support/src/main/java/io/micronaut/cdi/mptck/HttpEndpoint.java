/* Licensed under the Apache License, Version 2.0. */
package io.micronaut.cdi.mptck;

import com.sun.net.httpserver.*;
import java.net.*;
import java.util.concurrent.*;

/** Loopback HTTP transport for TCKs written for an application server. */
public final class HttpEndpoint implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final jakarta.enterprise.context.control.RequestContextController request;
    private final java.nio.file.Path evidence;
    private final String previousTestUrl;
    public HttpEndpoint(ClassLoader loader, HttpHandler handler) throws Exception {
        request = java.util.Objects.requireNonNull(CurrentDeployment.request, "HTTP endpoint needs a deployed request context");
        evidence = CurrentDeployment.evidence;
        previousTestUrl = System.getProperty("test.url");
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            ClassLoader old = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(loader);
            boolean active = false;
            try { active = request.activate(); handler.handle(exchange); }
            catch (Throwable failure) {
                java.io.StringWriter trace = new java.io.StringWriter();
                failure.printStackTrace(new java.io.PrintWriter(trace));
                java.nio.file.Files.writeString(evidence.resolve("http-failure-" + System.nanoTime() + ".txt"), trace.toString());
                respond(exchange, 500, "text/plain", failure.getClass().getName() + ": " + failure.getMessage());
            }
            finally {
                try {
                    exchange.close();
                    if (active) request.deactivate();
                } finally { Thread.currentThread().setContextClassLoader(old); }
            }
        });
        server.start();
        CurrentDeployment.uri = URI.create("http://localhost:" + server.getAddress().getPort());
        System.setProperty("test.url", CurrentDeployment.uri.toString());
    }
    public static void respond(HttpExchange exchange, int status, String contentType, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }
    @Override public void close() {
        server.stop(0);
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) throw new IllegalStateException("TCK HTTP handlers did not stop");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while stopping TCK HTTP handlers", e);
        } finally {
            if (previousTestUrl == null) System.clearProperty("test.url"); else System.setProperty("test.url", previousTestUrl);
        }
    }
}
