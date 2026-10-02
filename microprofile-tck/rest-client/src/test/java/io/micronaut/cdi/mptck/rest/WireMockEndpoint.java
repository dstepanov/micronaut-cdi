/* Licensed under the Apache License, Version 2.0. */
package io.micronaut.cdi.mptck.rest;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.micronaut.cdi.mptck.EndpointAdapter;
import org.testng.IExecutionListener;
import java.util.Set;

/** Starts the required stub server before even the TCK's BeforeTest callbacks run. */
public final class WireMockEndpoint implements EndpointAdapter, IExecutionListener {
    private static WireMockServer server;
    @Override public void onExecutionStart() {
        // Upstream deployment properties contain literal URLs on this port; keep their bytes unchanged.
        server = new WireMockServer(WireMockConfiguration.wireMockConfig().bindAddress("127.0.0.1").port(8765));
        server.start();
        System.setProperty("wiremock.server.host", "localhost");
        System.setProperty("wiremock.server.port", "8765");
        System.setProperty("wiremock.server.scheme", "http");
        System.setProperty("wiremock.server.context", "");
    }
    @Override public AutoCloseable start(Set<String> classes, ClassLoader loader) { return () -> { }; }
    @Override public void onExecutionFinish() { if (server != null) server.stop(); }
}
