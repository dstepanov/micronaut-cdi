package io.micronaut.cdi.test.groovy

import io.micronaut.cdi.internal.runtime.CdiBeanContainer
import io.micronaut.context.ApplicationContext
import io.micronaut.core.annotation.AnnotationValue
import jakarta.enterprise.context.Dependent
import jakarta.enterprise.util.Nonbinding
import jakarta.inject.Qualifier
import org.junit.jupiter.api.Test

import java.lang.annotation.Retention
import java.lang.annotation.RetentionPolicy

import static org.junit.jupiter.api.Assertions.assertEquals

/**
 * Two qualifier interfaces whose members are annotated with each other, compiled by the Groovy compiler: reading
 * the non-binding members of one reads the annotations of its members, which names the other, whose members name
 * the first. The compilation has to end, and the bean has to resolve by a value of the non-binding member it was
 * not given.
 */
class MutuallyAnnotatedQualifierMembersUnderGroovyTest {

    @Test
    void aQualifierWhoseMembersNameEachOtherCompilesAndResolves() {
        try (ApplicationContext context = ApplicationContext.run()) {
            def dial = context.getBean(CdiBeanContainer).createInstance()
                .select(Dial, AnnotationValue.builder(Clockwise).member("turns", "other").build())
            assertEquals(Dial, dial.get().getClass())
        }
    }
}

@Qualifier
@Retention(RetentionPolicy.RUNTIME)
@interface Clockwise {
    @Nonbinding @Anticlockwise String turns() default ""
}

@Qualifier
@Retention(RetentionPolicy.RUNTIME)
@interface Anticlockwise {
    @Nonbinding @Clockwise(turns = "one") String turns() default ""
}

@Dependent
@Clockwise(turns = "two")
class Dial {
}
