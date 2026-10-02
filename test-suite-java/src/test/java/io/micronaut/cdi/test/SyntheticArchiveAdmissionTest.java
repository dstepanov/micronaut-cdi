/* Licensed under the Apache License, Version 2.0. */
package io.micronaut.cdi.test;

import io.micronaut.cdi.se.MicronautSeContainerInitializer;
import io.micronaut.context.ApplicationContext;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.se.SeContainerInitializer;
import org.example.cdi.synthetic.SelectedArrayFactory;
import org.example.cdi.synthetic.OmittedArrayFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runtime synthetic bean admission follows its recorded creator's archive. */
class SyntheticArchiveAdmissionTest {
    @AfterEach
    void resetArchive() {
        MicronautSeContainerInitializer.restrictClasspath(null);
    }

    @Test
    void discoveryDisabledIncludesSelectedCreatorAndExcludesAnotherArrayCreator() {
        try (var container = SeContainerInitializer.newInstance().disableDiscovery()
            .addBeanClasses(SelectedArrayFactory.class).initialize()) {
            assertArrayEquals(new String[]{"selected"}, container.select(String[][].class, Any.Literal.INSTANCE).get()[0]);
            assertTrue(container.select(int[][].class, Any.Literal.INSTANCE).isUnsatisfied());
        }
    }

    @Test
    void restrictedClasspathIncludesSelectedCreatorAndExcludesAnotherArrayCreator() {
        Set<String> archive = unrelatedTestArchives();
        archive.add(SelectedArrayFactory.class.getName());
        MicronautSeContainerInitializer.restrictClasspath(archive);
        try (var container = SeContainerInitializer.newInstance().disableDiscovery()
            .addBeanClasses(SelectedArrayFactory.class).initialize()) {
            assertArrayEquals(new String[]{"selected"}, container.select(String[][].class, Any.Literal.INSTANCE).get()[0]);
            assertTrue(container.select(int[][].class, Any.Literal.INSTANCE).isUnsatisfied());
        }
    }

    @Test
    void selectingTheReturnedArrayTypeDoesNotAdmitItsUnselectedCreator() {
        try (var container = SeContainerInitializer.newInstance().disableDiscovery()
            .addBeanClasses(String[][].class, int[][].class).initialize()) {
            assertTrue(container.select(String[][].class, Any.Literal.INSTANCE).isUnsatisfied());
            assertTrue(container.select(int[][].class, Any.Literal.INSTANCE).isUnsatisfied());
        }
    }

    @Test
    void restrictedClasspathCanExcludeAnOtherwiseSelectedCreator() {
        MicronautSeContainerInitializer.restrictClasspath(unrelatedTestArchives());
        try (var container = SeContainerInitializer.newInstance().disableDiscovery()
            .addBeanClasses(SelectedArrayFactory.class).initialize()) {
            assertTrue(container.select(String[][].class, Any.Literal.INSTANCE).isUnsatisfied());
        }
    }

    private static Set<String> unrelatedTestArchives() {
        // Other test beans in the io.micronaut infrastructure namespace remain admitted by bootstrap.
        // Keep their application factories available, while independently restricting our two archives.
        Set<String> classes = new HashSet<>();
        try (ApplicationContext context = ApplicationContext.run()) {
            for (var definition : context.getAllBeanDefinitions()) {
                addOuter(classes, definition.getBeanType().getName());
                definition.getDeclaringType().ifPresent(type -> addOuter(classes, type.getName()));
            }
        }
        classes.remove(SelectedArrayFactory.class.getName());
        classes.remove(OmittedArrayFactory.class.getName());
        return classes;
    }

    private static void addOuter(Set<String> classes, String name) {
        int nested = name.indexOf('$');
        classes.add(nested < 0 ? name : name.substring(0, nested));
    }
}
