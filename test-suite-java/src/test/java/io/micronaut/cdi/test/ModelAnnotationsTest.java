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

/**
 * The language model reports a class the way its source reads, not the way Micronaut records it: what
 * Micronaut's own mappers add to a class is not an annotation the source wrote, and neither is the
 * {@code NonNull} Micronaut writes on the types a null-marked class declares.
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
    void whatMicronautAddsToAClassIsNotReportedAsAnAnnotationOfIt() {
        // ApplicationScoped is mapped to Micronaut's own scope annotations, which the source did not write.
        // NullMarked is not reported either: Micronaut remaps it to a marker of its own and the name the source
        // wrote is gone from the record, which only the annotations as written can restore
        // (MICRONAUT-CORE-FINDINGS.md, finding 39)
        assertEquals(Set.of(Recorded.class.getName(), ApplicationScoped.class.getName()),
            Set.of(seen().stringValues(Seen.class, "classAnnotations")));
    }

    @Test
    void theNonNullMicronautWritesOnATypeInANullMarkedClassIsNotReported() {
        assertEquals(List.of(), List.of(seen().stringValues(Seen.class, "fieldTypeAnnotations")));
    }

    private AnnotationMetadata seen() {
        return context.getBeanDefinition(Marked.class).getAnnotationMetadata();
    }
}
