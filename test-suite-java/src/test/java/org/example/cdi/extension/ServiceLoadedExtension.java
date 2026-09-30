package org.example.cdi.extension;

import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.spi.BeforeBeanDiscovery;
import jakarta.enterprise.inject.spi.Extension;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * A portable extension found through the service loader by every SE bootstrap of this module's tests: it
 * counts, and changes nothing.
 */
public class ServiceLoadedExtension implements Extension {

    static final AtomicInteger NOTIFIED = new AtomicInteger();

    void before(@Observes BeforeBeanDiscovery event) {
        NOTIFIED.incrementAndGet();
    }
}
