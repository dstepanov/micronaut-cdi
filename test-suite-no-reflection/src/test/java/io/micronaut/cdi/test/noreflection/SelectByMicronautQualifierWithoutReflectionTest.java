package io.micronaut.cdi.test.noreflection;

import io.micronaut.cdi.MicronautBeanContainer;
import io.micronaut.context.ApplicationContext;
import io.micronaut.core.type.Argument;
import io.micronaut.inject.qualifiers.Qualifiers;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Named;
import jakarta.inject.Qualifier;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Selecting by a Micronaut qualifier reads nothing back: no annotation instance is made and no class is read.
 */
class SelectByMicronautQualifierWithoutReflectionTest {

    @Qualifier
    @Retention(RetentionPolicy.RUNTIME)
    @interface Overnight {
    }

    interface Parcel {
        String name();
    }

    @Named("letter")
    @Dependent
    static class Letter implements Parcel {
        @Override
        public String name() {
            return "letter";
        }
    }

    @Overnight
    @Dependent
    static class Box implements Parcel {
        @Override
        public String name() {
            return "box";
        }
    }

    @Test
    void aLookupAndTheBeanContainerSelectByAMicronautQualifier() {
        try (ApplicationContext context = ApplicationContext.run()) {
            MicronautBeanContainer container = context.getBean(MicronautBeanContainer.class);
            assertEquals("letter", container.createInstance()
                .select(Parcel.class, Qualifiers.<Parcel>byName("letter")).get().name());
            assertEquals(List.of("box"), container.createInstance()
                .select(Argument.of(Parcel.class), Qualifiers.<Parcel>byStereotype(Overnight.class))
                .stream().map(Parcel::name).toList());
            assertEquals(1, container.getBeans(Argument.of(Parcel.class), Qualifiers.byName("letter")).size());
        }
    }
}
