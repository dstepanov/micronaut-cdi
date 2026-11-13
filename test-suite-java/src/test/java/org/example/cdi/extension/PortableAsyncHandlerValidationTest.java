package org.example.cdi.extension;

import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.se.SeContainer;
import jakarta.enterprise.inject.se.SeContainerInitializer;
import jakarta.enterprise.inject.spi.AfterDeploymentValidation;
import jakarta.enterprise.inject.spi.Extension;
import jakarta.enterprise.invoke.AsyncHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * A portable extension asks whether an async handler exists of the service entries the container's class loader
 * serves, where an invoker loads its handler, and the handler is read without being instantiated (CDI 5.0,
 * section 10.5).
 */
class PortableAsyncHandlerValidationTest {

    @TempDir
    Path entries;

    @Test
    void aHandlerTheContainersLoaderServesExistsWithoutBeingCreated() throws IOException {
        Path services = entries.resolve("META-INF/services");
        Files.createDirectories(services);
        Files.writeString(services.resolve("jakarta.enterprise.invoke.AsyncHandler$ReturnType"),
            RefusingHandler.class.getName() + "\n");
        try (URLClassLoader loader = new URLClassLoader(new URL[] {entries.toUri().toURL()},
            getClass().getClassLoader())) {
            assertDoesNotThrow(() -> {
                try (SeContainer container = SeContainerInitializer.newInstance().disableDiscovery()
                    .setClassLoader(loader).addExtensions(new Ensuring()).initialize()) {
                    container.isRunning();
                }
            });
        }
    }

    /**
     * Asks for a handler of {@link Async}.
     */
    public static final class Ensuring implements Extension {
        void validated(@Observes AfterDeploymentValidation event) {
            event.ensureAsyncHandlerExists(Async.class, () -> "the handler the loader serves");
        }
    }

    /**
     * The async type.
     */
    public interface Async {
    }

    /**
     * A handler that may not be created.
     */
    public static final class RefusingHandler implements AsyncHandler.ReturnType<Async> {
        public RefusingHandler() {
            throw new IllegalStateException("a handler is read, not created, to tell whether it exists");
        }

        @Override
        public Async transform(Async original, Runnable completion) {
            return original;
        }
    }
}
