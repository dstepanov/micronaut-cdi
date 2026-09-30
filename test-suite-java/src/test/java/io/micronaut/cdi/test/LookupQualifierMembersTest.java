package io.micronaut.cdi.test;

import io.micronaut.cdi.MicronautBeanContainer;
import io.micronaut.cdi.MicronautInjectionPoint;
import io.micronaut.cdi.MicronautInstance;
import io.micronaut.context.ApplicationContext;
import io.micronaut.core.annotation.AnnotationValue;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Default;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.se.SeContainer;
import jakarta.enterprise.inject.se.SeContainerInitializer;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.enterprise.inject.spi.InjectionPoint;
import jakarta.enterprise.util.AnnotationLiteral;
import jakarta.enterprise.util.Nonbinding;
import jakarta.inject.Inject;
import jakarta.inject.Qualifier;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The injection point of a bean obtained by a lookup that was not itself injected anywhere - through the bean
 * container, {@code CDI.current()} or the SE container - reports the required type and the required qualifiers
 * of the lookup (section 2.4.5.7), with every member of each, the ones that do not bind among them.
 */
class LookupQualifierMembersTest {

    @Qualifier
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Prefixed {
        @Nonbinding String prefix() default "";

        final class Literal extends AnnotationLiteral<Prefixed> implements Prefixed {
            private final String prefix;

            Literal(String prefix) {
                this.prefix = prefix;
            }

            @Override
            public String prefix() {
                return prefix;
            }
        }
    }

    @Qualifier
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Urgent {
    }

    static final AnnotationLiteral<Urgent> URGENT = new AnnotationLiteral<>() {
    };

    @Dependent
    @Prefixed
    @Urgent
    public static class PrefixedSettings {
        @Inject
        InjectionPoint at;

        String prefix() {
            for (Annotation qualifier : at.getQualifiers()) {
                if (qualifier instanceof Prefixed prefixed) {
                    return prefixed.prefix();
                }
            }
            return "none";
        }
    }

    @Dependent
    public static class PlainSettings {
        @Inject
        InjectionPoint at;
    }

    private static MicronautInstance<Object> lookup(ApplicationContext context) {
        return context.getBean(MicronautBeanContainer.class).createInstance();
    }

    @Test
    void theSelectedQualifierIsReportedWithItsNonbindingMember() {
        try (ApplicationContext context = ApplicationContext.run()) {
            PrefixedSettings settings = lookup(context)
                .select(PrefixedSettings.class, new Prefixed.Literal("client")).get();
            assertEquals("client", settings.prefix());
            assertEquals(PrefixedSettings.class, settings.at.getType());
            assertNull(settings.at.getBean(), "the lookup belongs to no bean");
            assertEquals("server", lookup(context)
                .select(PrefixedSettings.class, new Prefixed.Literal("server")).get().prefix());
        }
    }

    @Test
    void theInstanceHandedToTheLookupIsTheOneReported() {
        try (ApplicationContext context = ApplicationContext.run()) {
            Prefixed.Literal literal = new Prefixed.Literal("client");
            PrefixedSettings settings = lookup(context).select(PrefixedSettings.class, literal).get();
            assertEquals(Set.of(literal), settings.at.getQualifiers());
        }
    }

    @Test
    void theQualifiersOfEverySelectOfAChainAreReported() {
        try (ApplicationContext context = ApplicationContext.run()) {
            PrefixedSettings settings = lookup(context).select(URGENT)
                .select(PrefixedSettings.class, new Prefixed.Literal("client")).get();
            assertEquals("client", settings.prefix());
            assertEquals(2, settings.at.getQualifiers().size());
        }
    }

    @Test
    void everyWayOfObtainingTheBeanReportsTheSame() {
        try (ApplicationContext context = ApplicationContext.run()) {
            Instance<PrefixedSettings> selected = lookup(context)
                .select(PrefixedSettings.class, new Prefixed.Literal("client"));
            assertEquals("client", selected.iterator().next().prefix());
            assertEquals("client", selected.stream().findFirst().orElseThrow().prefix());
            assertEquals("client", selected.getHandle().get().prefix());
            assertEquals("client", selected.handles().iterator().next().get().prefix());
        }
    }

    @Test
    void aQualifierSelectedAsAnAnnotationValueIsReadWithoutAnInstance() {
        try (ApplicationContext context = ApplicationContext.run()) {
            PrefixedSettings settings = lookup(context).select(PrefixedSettings.class,
                AnnotationValue.builder(Prefixed.class).member("prefix", "client").build()).get();
            MicronautInjectionPoint at = assertInstanceOf(MicronautInjectionPoint.class, settings.at);
            List<AnnotationValue<?>> qualifiers = at.getQualifierValues();
            assertEquals(1, qualifiers.size());
            assertEquals(Prefixed.class.getName(), qualifiers.get(0).getAnnotationName());
            assertEquals("client", qualifiers.get(0).stringValue("prefix").orElse(null));
        }
    }

    @Test
    void aLookupThatSelectsNoQualifierRequiresTheDefaultOne() {
        try (ApplicationContext context = ApplicationContext.run()) {
            PlainSettings settings = lookup(context).select(PlainSettings.class).get();
            assertEquals(Set.of(Default.Literal.INSTANCE), settings.at.getQualifiers());
            MicronautInjectionPoint at = assertInstanceOf(MicronautInjectionPoint.class, settings.at);
            assertEquals(List.of(Default.class.getName()),
                at.getQualifierValues().stream().map(AnnotationValue::getAnnotationName).toList());
            PlainSettings any = lookup(context).select(PlainSettings.class, Any.Literal.INSTANCE).get();
            assertEquals(Set.of(Any.Literal.INSTANCE), any.at.getQualifiers());
            PlainSettings byDefault = lookup(context).select(PlainSettings.class, Default.Literal.INSTANCE).get();
            assertEquals(Set.of(Default.Literal.INSTANCE), byDefault.at.getQualifiers());
        }
    }

    @Test
    void theContainersOfTheSpecificationReportTheSame() {
        try (ApplicationContext context = ApplicationContext.run()) {
            assertEquals("client", CDI.current()
                .select(PrefixedSettings.class, new Prefixed.Literal("client")).get().prefix());
        }
        try (SeContainer container = SeContainerInitializer.newInstance().initialize()) {
            assertEquals("client", container
                .select(PrefixedSettings.class, new Prefixed.Literal("client")).get().prefix());
        }
    }
}
