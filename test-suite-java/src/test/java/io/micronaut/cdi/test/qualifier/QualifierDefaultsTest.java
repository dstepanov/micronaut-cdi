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
        assertTrue(CdiQualifier.ofValue(null, defaulted).matches(CdiQualifier.ofValue(null, written)));
        assertTrue(CdiQualifier.ofValue(null, written).matches(CdiQualifier.ofValue(null, defaulted)));
        assertFalse(CdiQualifier.ofValue(null, defaulted).matches(CdiQualifier.ofValue(null, other)));
    }

    @Test
    void anEmptyStringWithoutADefaultIsTheMemberLeftOut() {
        AnnotationValue<?> empty = AnnotationValue.builder(UNREGISTERED).member("name", "").build();
        AnnotationValue<?> none = AnnotationValue.builder(UNREGISTERED).build();
        assertTrue(CdiQualifier.ofValue(null, empty).matches(CdiQualifier.ofValue(null, none)));
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
        assertFalse(CdiQualifier.ofValue(null, empty).matches(CdiQualifier.ofValue(null, defaulted)));
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
        assertTrue(CdiQualifier.ofValue(null, listed).matches(CdiQualifier.ofValue(null, other)));
        AnnotationValue<?> dollar = AnnotationValue.builder(UNREGISTERED).member("$level", "a").build();
        AnnotationValue<?> otherDollar = AnnotationValue.builder(UNREGISTERED).member("$level", "b").build();
        assertFalse(CdiQualifier.ofValue(null, dollar).matches(CdiQualifier.ofValue(null, otherDollar)));
    }
}
