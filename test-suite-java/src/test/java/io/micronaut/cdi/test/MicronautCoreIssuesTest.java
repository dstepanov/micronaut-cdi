package io.micronaut.cdi.test;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Primary;
import io.micronaut.context.annotation.Prototype;
import jakarta.inject.Qualifier;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The behaviour of Micronaut itself that this module runs into, written the way a plain Micronaut application
 * would write it, so that it is not mistaken for something this module does.
 *
 * <p>A test of a difference not yet fixed is disabled rather than left out: a disabled test that says what is wrong
 * is what will fail the moment it is fixed, and it is the smallest reproduction of a difference recorded under
 * Conformance. Once Micronaut fixes it, the test runs, and pins the behaviour this module now relies on instead of
 * working around it.</p>
 */
class MicronautCoreIssuesTest {

    @Qualifier
    @Retention(RetentionPolicy.RUNTIME)
    @interface Marker {
    }

    record Thing(String name) {
    }

    @Factory
    @Singleton
    @Primary
    @Marker
    static class PrimaryAndQualifiedFactory {
        @Bean
        @Prototype
        Thing thing() {
            return new Thing("made");
        }
    }

    @Test
    void aFactoryThatIsPrimaryAndAlsoQualifiedCanProduceABean() {
        try (ApplicationContext context = ApplicationContext.run()) {
            assertEquals("made", context.getBean(Thing.class).name());
        }
    }

    @Prototype
    static class Gadget {
    }

    record Part(String name) {
    }

    @Factory
    static class PartLists {
        @Bean
        @Prototype
        java.util.List<Part> parts() {
            return java.util.List.of(new Part("bolt"));
        }
    }

    interface Engine {
    }

    @Singleton
    static class OldEngine implements Engine {
    }

    @Singleton
    @io.micronaut.context.annotation.Replaces(OldEngine.class)
    static class NewEngine implements Engine {
    }

    /**
     * A qualifier that keeps the replaced engine alone, which tells whether a lookup drops the replaced definition
     * before the qualifier sees the candidates or after.
     */
    static final class OnlyTheOldEngine implements io.micronaut.context.Qualifier<Object> {
        @Override
        public <B extends io.micronaut.inject.BeanType<Object>> java.util.stream.Stream<B> reduce(Class<Object> beanType,
                                                                                            java.util.stream.Stream<B> candidates) {
            return candidates.filter(candidate -> OldEngine.class.equals(candidate.getBeanType()));
        }
    }

    /**
     * Destroying a prototype leaves the candidates Micronaut keeps of its types (micronaut-core #14063), so that a
     * dependent bean created and destroyed over and over does not have the candidates of its types looked up again.
     */
    @Test
    @SuppressWarnings("unchecked")
    void destroyingAPrototypeKeepsTheCandidatesOfItsTypes() throws Exception {
        try (ApplicationContext context = ApplicationContext.run()) {
            context.getBeanDefinitions(Gadget.class);
            java.lang.reflect.Field field = io.micronaut.context.DefaultBeanContext.class
                .getDeclaredField("beanCandidateCache");
            field.setAccessible(true);
            java.util.Map<Object, Object> candidates = (java.util.Map<Object, Object>) field.get(context);
            assertTrue(candidates.containsKey(io.micronaut.core.type.Argument.of(Gadget.class)));
            context.destroyBean(context.getBean(Gadget.class));
            assertTrue(candidates.containsKey(io.micronaut.core.type.Argument.of(Gadget.class)),
                "the candidates of the type of a destroyed prototype are kept");
        }
    }

    /**
     * A raw lookup of a type a factory's {@code List} exposes - {@code Collection} - finds the factory's bean
     * (micronaut-core #14063): the container asks the bean context for the candidates of the raw class of a type.
     */
    @Test
    void aRawLookupOfATypeAFactoryOfAListExposesFindsIt() {
        try (ApplicationContext context = ApplicationContext.run()) {
            io.micronaut.inject.BeanDefinition<?> parts = context.getBeanDefinitions(java.util.List.class).stream()
                .filter(definition -> definition.getTypeArguments(java.util.List.class).stream()
                    .anyMatch(argument -> argument.getType() == Part.class))
                .findFirst().orElseThrow();
            assertTrue(parts.getExposedTypes().contains(java.util.Collection.class));
            assertTrue(context.getBeanDefinitions(java.util.Collection.class).contains(parts),
                "the definition is a candidate of a type it exposes");
        }
    }

    /**
     * {@code getBeanDefinitions(Qualifier)} drops a replaced definition before the qualifier, as
     * {@code getBeanDefinitions(Argument, Qualifier)} does (micronaut-core #14063): a qualifier that keeps the replaced
     * bean alone finds it through neither.
     */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void aReplacedBeanIsLeftOutBeforeTheQualifierWhicheverWayTheDefinitionsAreAskedFor() {
        try (ApplicationContext context = ApplicationContext.run()) {
            assertTrue(context.getBeanDefinitions((Class) Engine.class, (io.micronaut.context.Qualifier) new OnlyTheOldEngine())
                .isEmpty());
            assertTrue(context.getBeanDefinitions(new OnlyTheOldEngine()).isEmpty(),
                "the replaced engine is no candidate for a qualifier to keep");
        }
    }

    @Test
    @Disabled("TypeHierarchies.find loads the record anew each time it is asked: the names of the records are kept, "
        + "the records are not, so the container keeps the records it found (RecordedTypeIndex.hierarchies)")
    void aTypeHierarchyRecordIsFoundOnce() throws ClassNotFoundException {
        // a bean class of this suite that has a record
        Class<?> type = Class.forName("io.micronaut.cdi.test.index.BeanTypeRecordTest$Shelf");
        assertSame(io.micronaut.core.type.TypeHierarchies.find(type).orElseThrow(),
            io.micronaut.core.type.TypeHierarchies.find(type).orElseThrow());
    }
}
