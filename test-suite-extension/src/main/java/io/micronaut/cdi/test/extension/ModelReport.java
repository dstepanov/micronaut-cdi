
package io.micronaut.cdi.test.extension;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * What {@link LanguageModelExtension} read of a class, written onto the class for a test to read back at runtime:
 * the one way what happened inside the compiler reaches a test.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ModelReport {

    /** The names of the annotations of the class, comma separated. */
    String annotations();

    /** The kinds of the classes the fields of the class name, as {@code field:kind,kind;...}. */
    String kinds();

    /** What the type of the field {@code tagged} carries. */
    String fieldType();

    /** What the superclass carries. */
    String superType();

    /** The type parameters the class declares, with their bounds. */
    String typeParameters();

    /** The name of the class a constructor returns. */
    String constructorReturn();

    /** The names of the methods and fields of the class, inherited ones included, comma separated. */
    String members();

    /** The value of a member an annotation left to its default. */
    String defaults();

    /** What the receiver and the thrown type of a method carry. */
    String receiverAndThrows();

    /** Which readings of one thing compare equal. */
    String equality();
}
