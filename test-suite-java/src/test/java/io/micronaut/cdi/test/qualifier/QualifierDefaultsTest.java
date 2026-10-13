package io.micronaut.cdi.test.qualifier;

import io.micronaut.cdi.internal.runtime.CdiQualifier;
import io.micronaut.core.annotation.AnnotationUtil;
import io.micronaut.core.annotation.AnnotationValue;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two qualifiers are compared with the defaults each of them carries, which Micronaut need not have registered
 * for the annotation type, and in the shape the interceptor bindings are compared in: an empty string no default
 * is recorded for is the member left out, and of the members Micronaut keeps for itself only the list of
 * non-binding members and the stereotypes are left out.
 */
class QualifierDefaultsTest {

    // no compiled element carries the annotation, so Micronaut registers no defaults for it
    private static final String UNREGISTERED = "io.micronaut.cdi.test.qualifier.QualifierDefaultsTest$Unregistered";

    @Test
    void aMemberLeftAtTheDefaultTheValueCarriesMatchesTheDefaultWritten() {
        AnnotationValue<?> defaulted = AnnotationValue.builder(UNREGISTERED)
            .defaultValues(Map.of("level", "INFO"))
            .build();
        AnnotationValue<?> written = AnnotationValue.builder(UNREGISTERED)
            .member("level", "INFO")
            .defaultValues(Map.of("level", "INFO"))
            .build();
        AnnotationValue<?> other = AnnotationValue.builder(UNREGISTERED)
            .member("level", "DEBUG")
            .defaultValues(Map.of("level", "INFO"))
            .build();
        assertTrue(CdiQualifier.ofValue(defaulted).matches(CdiQualifier.ofValue(written)));
        assertTrue(CdiQualifier.ofValue(written).matches(CdiQualifier.ofValue(defaulted)));
        assertFalse(CdiQualifier.ofValue(defaulted).matches(CdiQualifier.ofValue(other)));
    }

    @Test
    void anEmptyStringWithoutADefaultIsTheMemberLeftOut() {
        AnnotationValue<?> empty = AnnotationValue.builder(UNREGISTERED).member("name", "").build();
        AnnotationValue<?> none = AnnotationValue.builder(UNREGISTERED).build();
        assertTrue(CdiQualifier.ofValue(empty).matches(CdiQualifier.ofValue(none)));
    }

    @Test
    void anEmptyStringIsNotADefaultThatIsNotEmpty() {
        AnnotationValue<?> empty = AnnotationValue.builder(UNREGISTERED)
            .member("level", "")
            .defaultValues(Map.of("level", "INFO"))
            .build();
        AnnotationValue<?> defaulted = AnnotationValue.builder(UNREGISTERED)
            .defaultValues(Map.of("level", "INFO"))
            .build();
        assertFalse(CdiQualifier.ofValue(empty).matches(CdiQualifier.ofValue(defaulted)));
    }

    @Test
    void onlyTheMembersMicronautListsAsItsOwnAreLeftOut() {
        AnnotationValue<?> listed = AnnotationValue.builder(UNREGISTERED)
            .member("level", "INFO")
            .member("note", "a")
            .member(AnnotationUtil.NON_BINDING_ATTRIBUTE, "note", AnnotationUtil.NON_BINDING_ATTRIBUTE)
            .build();
        AnnotationValue<?> other = AnnotationValue.builder(UNREGISTERED)
            .member("level", "INFO")
            .member("note", "b")
            .build();
        assertTrue(CdiQualifier.ofValue(listed).matches(CdiQualifier.ofValue(other)));
        AnnotationValue<?> dollar = AnnotationValue.builder(UNREGISTERED).member("$level", "a").build();
        AnnotationValue<?> otherDollar = AnnotationValue.builder(UNREGISTERED).member("$level", "b").build();
        assertFalse(CdiQualifier.ofValue(dollar).matches(CdiQualifier.ofValue(otherDollar)));
    }
}
