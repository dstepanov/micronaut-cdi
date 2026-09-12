package io.micronaut.cdi.test.extension;

import jakarta.enterprise.util.AnnotationLiteral;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * What the language model reported of a class to the {@link RecordingExtension}, put on the class for a test
 * to read back through the bean's metadata.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Seen {

    /**
     * @return The names of the class's annotations
     */
    String[] classAnnotations() default {};

    /**
     * @return The names of the annotations on the types of the class's fields, each as {@code field:name}
     */
    String[] fieldTypeAnnotations() default {};

    /**
     * The annotation as an instance, which is what an extension adds to a class.
     */
    final class Literal extends AnnotationLiteral<Seen> implements Seen {

        private final String[] classAnnotations;
        private final String[] fieldTypeAnnotations;

        public Literal(String[] classAnnotations, String[] fieldTypeAnnotations) {
            this.classAnnotations = classAnnotations;
            this.fieldTypeAnnotations = fieldTypeAnnotations;
        }

        @Override
        public String[] classAnnotations() {
            return classAnnotations;
        }

        @Override
        public String[] fieldTypeAnnotations() {
            return fieldTypeAnnotations;
        }
    }
}
