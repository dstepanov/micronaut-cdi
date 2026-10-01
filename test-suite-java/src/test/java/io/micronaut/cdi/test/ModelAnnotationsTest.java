package io.micronaut.cdi.test;

import io.micronaut.cdi.test.extension.Recorded;
import io.micronaut.cdi.test.extension.Seen;
import io.micronaut.context.ApplicationContext;
import io.micronaut.core.annotation.AnnotationMetadata;
import jakarta.enterprise.context.ApplicationScoped;
import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * By default the language model reports a class the way Micronaut records it: what Micronaut's own mappers add
 * to a class comes with it, and so does the non-null marker Micronaut writes on the types a null-marked class
 * declares. A deployment narrows that by registering a {@code LanguageModelAnnotationFilter}, as the
 * technology compatibility kit does.
 */
class ModelAnnotationsTest {

    @Recorded
    @ApplicationScoped
    @NullMarked
    static class Marked {
        String name = "";
        List<String> names = List.of();
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
    void whatMicronautAddsToAClassIsReportedWithWhatTheSourceWrote() {
        Set<String> seen = Set.of(seen().stringValues(Seen.class, "classAnnotations"));
        assertTrue(seen.contains(Recorded.class.getName()), seen.toString());
        assertTrue(seen.contains(ApplicationScoped.class.getName()), seen.toString());
        // ApplicationScoped is mapped to Micronaut's own scope annotation, which is reported too
        assertTrue(seen.contains("io.micronaut.cdi.internal.metadata.CdiScope"), seen.toString());
        // NullMarked is remapped to a marker of Micronaut's own; the name the source wrote is gone from the record
        assertTrue(seen.contains("io.micronaut.core.annotation.NullMarked"), seen.toString());
    }

    @Test
    void theNonNullMicronautWritesOnATypeInANullMarkedClassIsReported() {
        assertEquals(List.of("name:jakarta.annotation.Nonnull", "names:jakarta.annotation.Nonnull"),
            List.of(seen().stringValues(Seen.class, "fieldTypeAnnotations")));
    }

    private AnnotationMetadata seen() {
        return context.getBeanDefinition(Marked.class).getAnnotationMetadata();
    }
}
