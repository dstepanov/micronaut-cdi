package io.micronaut.cdi.test;

import io.micronaut.context.ApplicationContext;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * What a lookup injected into a bean does once Micronaut no longer resolves through that bean's own resolver: used
 * after the bean was destroyed, by a reference that escaped it, and used from another thread by the bean's
 * {@code @PreDestroy} while it is destroyed and the container keeps running. In both the lookup creates the instance
 * through a dependency group of its own. The group opened by the {@code @PreDestroy} is closed as the bean's
 * destruction completes; the one opened after the bean was destroyed is closed by nothing, and what it created is
 * not destroyed even as the container stops.
 */
class OwnerlessLookupTest {

    /**
     * How long the work a {@code @PreDestroy} hands to another thread is waited for: a lookup that blocks on the
     * destruction it runs within is reported as a failure rather than hanging the suite.
     */
    private static final long WAIT_SECONDS = 10;

    static final List<String> SEEN = new CopyOnWriteArrayList<>();
    static volatile boolean armed;

    @Dependent
    public static class Helper {
        private String tag = "";

        String help(String tag) {
            this.tag = tag;
            return "helped " + tag;
        }

        @PreDestroy
        void destroyed() {
            if (armed) {
                SEEN.add("helper destroyed " + tag);
            }
        }
    }

    @Dependent
    public static class Owner {
        @Inject
        Instance<Helper> helpers;
    }

    @Dependent
    public static class OffThreadOwner {
        @Inject
        Instance<Helper> helpers;

        @PreDestroy
        void destroyed() throws InterruptedException {
            if (!armed) {
                return;
            }
            SEEN.add("closing");
            ExecutorService executor = Executors.newSingleThreadExecutor();
            Future<String> work = executor.submit(() -> helpers.get().help("off-thread"));
            try {
                SEEN.add(work.get(WAIT_SECONDS, TimeUnit.SECONDS));
            } catch (ExecutionException e) {
                SEEN.add("failed: " + e.getCause());
            } catch (TimeoutException e) {
                SEEN.add("timed out");
            } finally {
                work.cancel(true);
                executor.shutdownNow();
            }
            SEEN.add("closed");
        }
    }

    @Test
    void aLookupThatEscapedItsDestroyedOwnerStillCreatesWhatNothingDestroys() {
        SEEN.clear();
        try (ApplicationContext context = ApplicationContext.run()) {
            armed = true;
            Instance<Owner> owners = CDI.current().select(Owner.class);
            Owner owner = owners.get();
            Instance<Helper> escaped = owner.helpers;
            // a helper created through the owner, whose destruction shows the owner was destroyed
            SEEN.add(escaped.get().help("owned"));
            owners.destroy(owner);
            try {
                SEEN.add(escaped.get().help("escaped"));
            } catch (RuntimeException e) {
                SEEN.add("failed: " + e);
            }
            SEEN.add("stopping");
        } finally {
            armed = false;
        }
        // the escaped helper is not destroyed: its group was opened after the owner had closed its dependents
        assertEquals(List.of("helped owned", "helper destroyed owned", "helped escaped", "stopping"), SEEN);
    }

    @Test
    void aLookupFromAPreDestroyOnAnotherThreadCreatesWhileTheContainerRuns() {
        SEEN.clear();
        try (ApplicationContext context = ApplicationContext.run()) {
            armed = true;
            Instance<OffThreadOwner> owners = CDI.current().select(OffThreadOwner.class);
            owners.destroy(owners.get());
            SEEN.add("owner destroyed");
        } finally {
            armed = false;
        }
        assertEquals(List.of("closing", "helped off-thread", "closed", "helper destroyed off-thread",
            "owner destroyed"), SEEN);
    }
}
