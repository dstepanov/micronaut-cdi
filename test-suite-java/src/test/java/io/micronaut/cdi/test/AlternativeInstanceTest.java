package io.micronaut.cdi.test;

import io.micronaut.context.ApplicationContext;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Alternative;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Iterator;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlternativeInstanceTest {

    interface Common {
        boolean ping();
    }

    @Dependent
    static class Bar implements Common {
        @Override
        public boolean ping() {
            return false;
        }
    }

    @Alternative
    @Priority(1400)
    @Dependent
    static class Baz implements Common {
        @Override
        public boolean ping() {
            return true;
        }
    }

    @ApplicationScoped
    public static class Obtains {
        @Inject
        Instance<Common> common;

        Instance<Common> common() {
            return common;
        }
    }

    @Test
    void aSelectedAlternativeIsTheOneTheLookupIterates() {
        try (ApplicationContext context = ApplicationContext.run()) {
            Instance<Common> instance = context.getBean(Obtains.class).common();
            assertFalse(instance.isAmbiguous(), "the alternative resolves the ambiguity");
            Iterator<Common> iterator = instance.iterator();
            assertTrue(iterator.hasNext(), "the iteration holds the alternative");
            assertTrue(iterator.next() instanceof Baz);
            assertFalse(iterator.hasNext());
            assertTrue(instance.get().ping());
        }
    }
}
