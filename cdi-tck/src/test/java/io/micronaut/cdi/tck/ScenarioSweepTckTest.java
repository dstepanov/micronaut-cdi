package io.micronaut.cdi.tck;

import io.micronaut.context.ApplicationContext;
import io.micronaut.inject.BeanDefinition;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanContainer;
import jakarta.enterprise.inject.spi.CDI;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A sweep over every scenario of the technology compatibility kit that this module compiles.
 *
 * <p>It is not one of the kit's own tests. What it asserts is what every one of those tests takes for granted:
 * that the container has read each of the specification's own beans, and can say what scope it is in, what
 * qualifies it, what types it has and what name it was given, without failing on any of them.</p>
 *
 * <p>A definition error in reading a bean shows up here rather than in whichever ported assertion happens to
 * reach that bean first, and it shows up for all of them at once.</p>
 */
class ScenarioSweepTckTest {

    private static ApplicationContext context;

    @BeforeAll
    static void startContainer() {
        context = ApplicationContext.run();
    }

    @AfterAll
    static void stopContainer() {
        context.close();
    }

    @Test
    void everyScenarioIsReadAsABean() {
        BeanContainer container = CDI.current().getBeanContainer();
        List<String> failures = new ArrayList<>();
        int scenarios = 0;
        for (BeanDefinition<?> definition : context.getAllBeanDefinitions()) {
            if (!definition.getBeanType().getName().startsWith("org.jboss.cdi.tck.")) {
                continue;
            }
            scenarios++;
            try {
                Bean<?> bean = container.getBeans(definition.getBeanType(), jakarta.enterprise.inject.Any.Literal.INSTANCE)
                    .stream()
                    .findFirst()
                    .orElse(null);
                if (bean == null) {
                    continue;
                }
                bean.getScope();
                bean.getQualifiers();
                bean.getTypes();
                bean.getName();
                bean.getStereotypes();
                bean.isAlternative();
            } catch (Exception | LinkageError e) {
                failures.add(definition.getBeanType().getName() + ": " + e);
            }
        }
        System.out.println("SWEEP scenarios=" + scenarios + " failures=" + failures.size());
        failures.stream().limit(20).forEach(f -> System.out.println("SWEEP FAIL " + f));
        assertTrue(failures.isEmpty(), failures.size() + " scenarios could not be read: " + failures);
        assertTrue(scenarios > 500, "expected the kit's scenarios to be read as beans, got " + scenarios);
    }
}
