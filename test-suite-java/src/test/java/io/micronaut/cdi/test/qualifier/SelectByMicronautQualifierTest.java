package io.micronaut.cdi.test.qualifier;

import io.micronaut.cdi.MicronautBeanContainer;
import io.micronaut.cdi.MicronautInstance;
import io.micronaut.context.ApplicationContext;
import io.micronaut.core.type.Argument;
import io.micronaut.inject.qualifiers.Qualifiers;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.InjectionPoint;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Qualifier;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A lookup and the bean container select by a Micronaut qualifier beside the qualifiers of the specification: by
 * name, by stereotype, by type, and combined with a qualifier of the specification.
 */
class SelectByMicronautQualifierTest {

    @Qualifier
    @Retention(RetentionPolicy.RUNTIME)
    @interface Express {
    }

    interface Courier {
        String name();
    }

    @Named("pigeon")
    @Dependent
    static class Pigeon implements Courier {
        @Inject
        InjectionPoint at;

        @Override
        public String name() {
            return "pigeon";
        }
    }

    @Express
    @Dependent
    static class Drone implements Courier {
        @Override
        public String name() {
            return "drone";
        }
    }

    @Dependent
    static class Walker implements Courier {
        @Override
        public String name() {
            return "walker";
        }
    }

    private static MicronautInstance<Object> lookup(ApplicationContext context) {
        return context.getBean(MicronautBeanContainer.class).createInstance();
    }

    @Test
    void aLookupIsNarrowedByName() {
        try (ApplicationContext context = ApplicationContext.run()) {
            Courier pigeon = lookup(context).select(Courier.class, Qualifiers.<Courier>byName("pigeon")).get();
            assertEquals("pigeon", pigeon.name());
            // the injection point reports the qualifiers of the specification alone, the Micronaut one having no
            // annotation to report: a qualifier selected, so not the default one
            assertEquals(Set.of(Any.Literal.INSTANCE), ((Pigeon) pigeon).at.getQualifiers());
        }
    }

    @Test
    void aLookupIsNarrowedByStereotypeAndByAQualifierOfTheSpecification() {
        try (ApplicationContext context = ApplicationContext.run()) {
            assertEquals(List.of("drone"), lookup(context)
                .select(Argument.of(Courier.class), Qualifiers.<Courier>byStereotype(Express.class))
                .stream().map(Courier::name).toList());
            assertEquals(List.of(), lookup(context)
                .select(Courier.class, new jakarta.enterprise.util.AnnotationLiteral<Express>() { })
                .select(Courier.class, Qualifiers.<Courier>byName("pigeon"))
                .stream().map(Courier::name).toList());
        }
    }

    @Test
    void aLookupNarrowedByAMicronautQualifierNoLongerRequiresTheDefaultQualifier() {
        try (ApplicationContext context = ApplicationContext.run()) {
            assertEquals(Set.of("pigeon", "drone", "walker"), Set.copyOf(lookup(context)
                .select(Courier.class, Qualifiers.<Courier>byType(Courier.class))
                .stream().map(Courier::name).toList()));
            // where an unqualified lookup leaves out the bean that has a qualifier of its own
            assertEquals(Set.of("pigeon", "walker"), Set.copyOf(lookup(context).select(Courier.class).stream()
                .map(Courier::name).toList()));
        }
    }

    @Test
    void theBeanContainerAnswersTheBeansAMicronautQualifierKeeps() {
        try (ApplicationContext context = ApplicationContext.run()) {
            Set<Bean<?>> beans = context.getBean(MicronautBeanContainer.class)
                .getBeans(Argument.of(Courier.class), Qualifiers.byStereotype(Express.class));
            assertEquals(Set.of(Drone.class), Set.of(beans.iterator().next().getBeanClass()));
            assertEquals(1, beans.size());
        }
    }
}
