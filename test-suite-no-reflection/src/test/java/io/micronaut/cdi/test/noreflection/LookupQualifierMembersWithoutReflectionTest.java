package io.micronaut.cdi.test.noreflection;

import io.micronaut.cdi.MicronautBeanContainer;
import io.micronaut.cdi.MicronautInjectionPoint;
import io.micronaut.cdi.MicronautInstance;
import io.micronaut.context.ApplicationContext;
import io.micronaut.core.annotation.AnnotationValue;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Default;
import jakarta.enterprise.inject.spi.InjectionPoint;
import jakarta.enterprise.util.Nonbinding;
import jakarta.inject.Inject;
import jakarta.inject.Qualifier;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * The qualifiers a lookup that was injected nowhere selected are read from the injection point of the bean it
 * obtains as the values they were selected with, a non-binding member included, with no annotation instance
 * made and no class read.
 */
class LookupQualifierMembersWithoutReflectionTest {

    @Qualifier
    @Retention(RetentionPolicy.RUNTIME)
    public @interface NoReflectionPrefixed {
        @Nonbinding String prefix() default "";
    }

    @Dependent
    @NoReflectionPrefixed
    public static class NoReflectionPrefixedSettings {
        @Inject
        InjectionPoint at;
    }

    @Dependent
    public static class NoReflectionPlainSettings {
        @Inject
        InjectionPoint at;
    }

    private static MicronautInstance<Object> lookup(ApplicationContext context) {
        return context.getBean(MicronautBeanContainer.class).createInstance();
    }

    private static AnnotationValue<?> prefixed(String prefix) {
        return AnnotationValue.builder(NoReflectionPrefixed.class).member("prefix", prefix).build();
    }

    @Test
    void aQualifierSelectedAsAnAnnotationValueIsReportedWithItsNonbindingMember() {
        try (ApplicationContext context = ApplicationContext.run()) {
            NoReflectionPrefixedSettings settings = lookup(context)
                .select(NoReflectionPrefixedSettings.class, prefixed("client")).get();
            MicronautInjectionPoint at = assertInstanceOf(MicronautInjectionPoint.class, settings.at);
            List<AnnotationValue<?>> qualifiers = at.getQualifierValues();
            assertEquals(1, qualifiers.size());
            assertEquals(NoReflectionPrefixed.class.getName(), qualifiers.get(0).getAnnotationName());
            assertEquals("client", qualifiers.get(0).stringValue("prefix").orElse(null));
        }
    }

    @Test
    void everyWayOfObtainingTheBeanReportsTheSame() {
        try (ApplicationContext context = ApplicationContext.run()) {
            MicronautInstance<NoReflectionPrefixedSettings> selected = lookup(context)
                .select(NoReflectionPrefixedSettings.class, prefixed("server"));
            for (NoReflectionPrefixedSettings settings : List.of(selected.get(), selected.iterator().next(),
                selected.stream().findFirst().orElseThrow(), selected.getHandle().get(),
                selected.handles().iterator().next().get())) {
                assertEquals("server", ((MicronautInjectionPoint) settings.at).getQualifierValues().get(0)
                    .stringValue("prefix").orElse(null));
            }
        }
    }

    @Test
    void aLookupThatSelectsNoQualifierRequiresTheDefaultOne() {
        try (ApplicationContext context = ApplicationContext.run()) {
            NoReflectionPlainSettings settings = lookup(context).select(NoReflectionPlainSettings.class).get();
            MicronautInjectionPoint at = assertInstanceOf(MicronautInjectionPoint.class, settings.at);
            assertEquals(List.of(Default.class.getName()),
                at.getQualifierValues().stream().map(AnnotationValue::getAnnotationName).toList());
        }
    }
}
