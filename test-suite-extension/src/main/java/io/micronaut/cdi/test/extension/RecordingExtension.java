package io.micronaut.cdi.test.extension;

import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.ClassConfig;
import jakarta.enterprise.inject.build.compatible.spi.Enhancement;
import jakarta.enterprise.lang.model.AnnotationInfo;
import jakarta.enterprise.lang.model.declarations.FieldInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Records what the language model reports of a {@link Recorded} class: its annotations and the annotations on
 * the types of its fields, put on the class as a {@link Seen} annotation for a test to read back.
 */
public final class RecordingExtension implements BuildCompatibleExtension {

    /**
     * Records the annotations of the class and of its fields' types.
     *
     * @param config The class
     */
    @Enhancement(types = Object.class, withSubtypes = true, withAnnotations = Recorded.class)
    public void record(ClassConfig config) {
        List<String> classAnnotations = new ArrayList<>();
        for (AnnotationInfo annotation : config.info().annotations()) {
            classAnnotations.add(annotation.name());
        }
        List<String> fieldTypeAnnotations = new ArrayList<>();
        for (FieldInfo field : config.info().fields()) {
            for (AnnotationInfo annotation : field.type().annotations()) {
                fieldTypeAnnotations.add(field.name() + ":" + annotation.name());
            }
        }
        config.addAnnotation(new Seen.Literal(classAnnotations.toArray(String[]::new),
            fieldTypeAnnotations.toArray(String[]::new)));
    }
}
