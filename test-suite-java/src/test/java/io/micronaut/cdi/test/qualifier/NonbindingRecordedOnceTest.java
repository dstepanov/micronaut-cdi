package io.micronaut.cdi.test.qualifier;

import io.micronaut.cdi.MicronautBeanContainer;
import io.micronaut.cdi.MicronautInstance;
import io.micronaut.cdi.test.extension.Ripe;
import io.micronaut.context.ApplicationContext;
import io.micronaut.core.annotation.AnnotationUtil;
import io.micronaut.core.annotation.AnnotationValue;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.util.AnnotationLiteral;
import jakarta.enterprise.util.Nonbinding;
import jakarta.inject.Qualifier;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The members of a qualifier that take no part in resolution are recorded once, under {@code $nonBinding} on every
 * occurrence the processor compiles: one that leaves a non-binding member at its default as much as one that writes
 * it, and a member a build compatible extension marked as much as one the annotation interface marks. A lookup
 * that writes such a member down, as an {@code AnnotationValue} or as a literal, selects the bean whatever it
 * wrote.
 */
class NonbindingRecordedOnceTest {

    @Qualifier
    @Retention(RetentionPolicy.RUNTIME)
    @interface Tone {

        String value();

        @Nonbinding String note() default "";
    }

    static final class ToneLiteral extends AnnotationLiteral<Tone> implements Tone {

        private final String value;
        private final String note;

        ToneLiteral(String value, String note) {
            this.value = value;
            this.note = note;
        }

        @Override
        public String value() {
            return value;
        }

        @Override
        public String note() {
            return note;
        }
    }

    interface Sound {
    }

    @Tone("loud")
    @Dependent
    static class Loud implements Sound {
    }

    @Tone("quiet")
    @Dependent
    static class Quiet implements Sound {
    }

    interface Fruit {
    }

    @Ripe(value = "yes", note = "written on the bean")
    @Dependent
    static class RipeFruit implements Fruit {
    }

    @Ripe("no")
    @Dependent
    static class GreenFruit implements Fruit {
    }

    private static MicronautInstance<Object> lookup(ApplicationContext context) {
        return context.getBean(MicronautBeanContainer.class).createInstance();
    }

    @Test
    void aBeanThatLeavesTheMemberAtItsDefaultListsItAsNonbinding() {
        try (ApplicationContext context = ApplicationContext.run()) {
            AnnotationValue<Tone> tone = context.getBeanDefinition(Loud.class).getAnnotationMetadata()
                .getAnnotation(Tone.class);
            assertTrue(List.of(tone.stringValues(AnnotationUtil.NON_BINDING_ATTRIBUTE)).contains("note"),
                () -> tone.toString());
        }
    }

    @Test
    void aLookupThatWritesTheMemberDownSelectsABeanThatLeftItAtItsDefault() {
        try (ApplicationContext context = ApplicationContext.run()) {
            assertEquals(List.of(Loud.class), lookup(context).select(Sound.class,
                    AnnotationValue.builder(Tone.class).value("loud").member("note", "anything").build())
                .stream().map(Object::getClass).toList());
            assertEquals(List.of(Quiet.class), lookup(context).select(Sound.class, new ToneLiteral("quiet", "x"))
                .stream().map(Object::getClass).toList());
        }
    }

    @Test
    void aMemberAnExtensionMarkedIsListedAsNonbinding() {
        try (ApplicationContext context = ApplicationContext.run()) {
            AnnotationValue<Ripe> ripe = context.getBeanDefinition(GreenFruit.class).getAnnotationMetadata()
                .getAnnotation(Ripe.class);
            assertTrue(List.of(ripe.stringValues(AnnotationUtil.NON_BINDING_ATTRIBUTE)).contains("note"),
                () -> ripe.toString());
        }
    }

    @Test
    void aLookupOfAMemberAnExtensionMarkedSelectsWhateverItWrote() {
        try (ApplicationContext context = ApplicationContext.run()) {
            assertEquals(List.of(RipeFruit.class), lookup(context).select(Fruit.class,
                    AnnotationValue.builder(Ripe.class).value("yes").member("note", "another").build())
                .stream().map(Object::getClass).toList());
            assertEquals(List.of(RipeFruit.class), lookup(context).select(Fruit.class,
                    new Ripe.Literal("yes", "of the literal"))
                .stream().map(Object::getClass).toList());
            assertEquals(List.of(GreenFruit.class), lookup(context).select(Fruit.class,
                    new Ripe.Literal("no", "of the literal"))
                .stream().map(Object::getClass).toList());
        }
    }
}
