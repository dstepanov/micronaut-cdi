package io.micronaut.cdi.test;

import io.micronaut.cdi.test.extension.AddedQualifier;
import io.micronaut.cdi.test.extension.MarkTheField;
import io.micronaut.context.ApplicationContext;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FieldEnhancementTest {

    public interface Voice {
        String speak();
    }

    @Dependent
    public static class Plain implements Voice {
        @Override
        public String speak() {
            return "plain";
        }
    }

    @Dependent
    @AddedQualifier
    public static class Qualified implements Voice {
        @Override
        public String speak() {
            return "qualified";
        }
    }

    @Dependent
    public static class Speaker {
        @Inject
        @MarkTheField
        Voice voice;
    }

    @Dependent
    public static class SilencedObserver {
        static boolean heard;

        @MarkTheField
        String marker;

        void silenced(@jakarta.enterprise.event.Observes String event) {
            heard = true;
        }
    }

    @Test
    void anObserverWhoseParameterAnnotationsWereRemovedHearsNothing() {
        SilencedObserver.heard = false;
        try (ApplicationContext context = ApplicationContext.run()) {
            context.getBean(jakarta.enterprise.inject.spi.BeanManager.class)
                .getEvent().select(String.class).fire("shout");
            org.junit.jupiter.api.Assertions.assertFalse(SilencedObserver.heard,
                "the parameter's Observes was removed, so the method is no observer");
        }
    }

    @Test
    void aQualifierAnExtensionPutsOnAFieldQualifiesTheInjection() {
        try (ApplicationContext context = ApplicationContext.run()) {
            assertEquals("qualified", context.getBean(Speaker.class).voice.speak(),
                "the field the extension qualified injects the qualified bean");
        }
    }
}
