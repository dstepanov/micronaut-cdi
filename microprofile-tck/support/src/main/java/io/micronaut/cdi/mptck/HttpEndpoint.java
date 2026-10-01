/* Licensed under the Apache License, Version 2.0. */
package io.micronaut.cdi.mptck;

import com.sun.net.httpserver.*;
import java.net.*;
import java.util.concurrent.*;

/** Loopback HTTP transport for TCKs written for an application server. */
public final class HttpEndpoint implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    public HttpEndpoint(ClassLoader loader, HttpHandler handler) throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            ClassLoader old = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(loader);
            boolean active = CurrentDeployment.request.activate();
            try { handler.handle(exchange); }
            catch (Throwable failure) {
                java.io.StringWriter trace = new java.io.StringWriter();
                failure.printStackTrace(new java.io.PrintWriter(trace));
                java.nio.file.Files.writeString(CurrentDeployment.evidence.resolve("http-failure-" + System.nanoTime() + ".txt"), trace.toString());
                respond(exchange, 500, "text/plain", failure.getClass().getName() + ": " + failure.getMessage());
            }
            finally {
                exchange.close();
                if (active) CurrentDeployment.request.deactivate();
                Thread.currentThread().setContextClassLoader(old);
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
    @Override public void close() { server.stop(0); executor.shutdownNow(); }
}
