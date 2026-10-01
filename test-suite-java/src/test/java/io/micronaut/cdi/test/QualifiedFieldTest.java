package io.micronaut.cdi.test;

import io.micronaut.context.ApplicationContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A field is injected where it is annotated {@code Inject}, and nowhere else (CDI 4.1 section 3.6): a qualifier on
 * a field that is not makes no injection point of it, so the deployment is valid and the field keeps its value.
 */
class QualifiedFieldTest {

    @Test
    void aQualifiedFieldWithoutInjectIsNotInjected() throws Exception {
        try (ApplicationContext context = InMemoryDeployment.start("valid.Consumer", """
            package valid;

            @jakarta.enterprise.context.Dependent
            public class Consumer {
                @jakarta.inject.Named("nothing.here")
                public String notInjected = "kept";
            }
            """)) {
            Class<?> type = context.getClassLoader().loadClass("valid.Consumer");
            Object consumer = context.getBean(type);
            assertEquals("kept", type.getField("notInjected").get(consumer));
        }
    }
}
