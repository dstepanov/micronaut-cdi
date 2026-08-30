package io.micronaut.cdi.test;

import io.micronaut.context.ApplicationContext;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InstanceBulkDestroyTest {

    static final List<Part> DESTROYED_PARTS = new ArrayList<>();
    static final List<Machine> DESTROYED_MACHINES = new ArrayList<>();

    @Dependent
    public static class Part {
        @PreDestroy
        void down() {
            DESTROYED_PARTS.add(this);
        }
    }

    @Dependent
    public static class Machine {
        private static final AtomicInteger GENERATOR = new AtomicInteger();
        private final int id = GENERATOR.incrementAndGet();

        @Inject
        Part part;

        public void ping() {
        }

        @PreDestroy
        void down() {
            DESTROYED_MACHINES.add(this);
        }

        @Override
        public int hashCode() {
            return 31 + id;
        }

        @Override
        public boolean equals(Object obj) {
            return obj instanceof Machine other && other.id == id;
        }
    }

    @ApplicationScoped
    public static class Shop {
        @Inject
        Instance<Machine> machines;

        Instance<Machine> machines() {
            return machines;
        }
    }

    @Test
    void destroyingEachInstanceDestroysItAndItsDependents() {
        DESTROYED_PARTS.clear();
        DESTROYED_MACHINES.clear();
        try (ApplicationContext context = ApplicationContext.run()) {
            Instance<Machine> instance = context.getBean(Shop.class).machines();
            List<Machine> machines = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                Machine machine = instance.get();
                machine.ping();
                machines.add(machine);
            }
            for (Machine machine : machines) {
                instance.destroy(machine);
            }
            assertEquals(machines, DESTROYED_MACHINES, "every machine destroyed, in order");
            assertEquals(10, DESTROYED_PARTS.size(), "each machine's part destroyed with it");
        }
    }
}
