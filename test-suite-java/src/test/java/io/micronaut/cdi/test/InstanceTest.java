package io.micronaut.cdi.test;

import io.micronaut.context.ApplicationContext;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.UnsatisfiedResolutionException;
import jakarta.enterprise.util.AnnotationLiteral;
import jakarta.inject.Inject;
import jakarta.inject.Qualifier;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstanceTest {

    @Qualifier
    @Retention(RetentionPolicy.RUNTIME)
    @interface Sharp {
    }

    @SuppressWarnings("all")
    static final class SharpLiteral extends AnnotationLiteral<Sharp> implements Sharp {
    }

    interface Tool {
        String name();
    }

    @Dependent
    static class Hammer implements Tool {
        @Override
        public String name() {
            return "hammer";
        }
    }

    @Dependent
    @Sharp
    static class Knife implements Tool {
        static final List<String> DESTROYED = new ArrayList<>();

        @Override
        public String name() {
            return "knife";
        }

        @jakarta.annotation.PreDestroy
        void gone() {
            DESTROYED.add(name());
        }
    }

    @Singleton
    static class Toolbox {
        @Inject
        Instance<Tool> tool;

        @Inject
        @Any
        Instance<Tool> everyTool;

        @Inject
        @Sharp
        Instance<Tool> sharpTools;

        @Inject
        Instance<Runnable> nothing;
    }

    private ApplicationContext context;

    @BeforeEach
    void start() {
        context = ApplicationContext.run();
    }

    @AfterEach
    void stop() {
        context.close();
    }

    @Test
    void anUnqualifiedLookupResolvesTheDefaultBean() {
        assertEquals("hammer", context.getBean(Toolbox.class).tool.get().name());
    }

    @Test
    void aLookupOfAnyResolvesEveryBeanOfTheType() {
        Instance<Tool> tools = context.getBean(Toolbox.class).everyTool;
        assertTrue(tools.isAmbiguous());
        List<String> names = new ArrayList<>();
        tools.forEach(tool -> names.add(tool.name()));
        assertEquals(2, names.size(), names.toString());
        assertTrue(names.contains("hammer") && names.contains("knife"), names.toString());
    }

    @Test
    void aQualifiedLookupResolvesTheQualifiedBean() {
        assertEquals("knife", context.getBean(Toolbox.class).sharpTools.get().name());
    }

    @Test
    void aLookupThatResolvesNothingSaysSo() {
        Instance<Runnable> nothing = context.getBean(Toolbox.class).nothing;
        assertTrue(nothing.isUnsatisfied());
        assertFalse(nothing.isResolvable());
        assertThrows(UnsatisfiedResolutionException.class, nothing::get);
    }

    @Test
    void selectNarrowsALookupByQualifier() {
        Instance<Tool> tools = context.getBean(Toolbox.class).everyTool;
        assertEquals("knife", tools.select(new SharpLiteral()).get().name());
    }

    @Test
    void aHandleDestroysTheBeanItResolved() {
        Knife.DESTROYED.clear();
        Instance<Tool> sharp = context.getBean(Toolbox.class).sharpTools;
        try (Instance.Handle<Tool> handle = sharp.getHandle()) {
            assertEquals("knife", handle.get().name());
            assertEquals(Knife.class, handle.getBean().getBeanClass());
        }
        assertEquals(List.of("knife"), Knife.DESTROYED);
    }
}
