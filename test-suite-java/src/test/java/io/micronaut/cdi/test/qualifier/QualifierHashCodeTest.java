package io.micronaut.cdi.test.qualifier;

import io.micronaut.context.ApplicationContext;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.enterprise.util.AnnotationLiteral;
import jakarta.enterprise.util.Nonbinding;
import jakarta.inject.Qualifier;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two qualifiers the container calls equivalent hash alike, over the members that bind: an enum member by its
 * constant, a member left at the empty string, and a non-binding member left out.
 */
class QualifierHashCodeTest {

    enum Grade {
        GOLD, SILVER
    }

    @Qualifier
    @Retention(RetentionPolicy.RUNTIME)
    @interface Rank {
        Grade grade();

        String label() default "";

        @Nonbinding
        String note() default "";
    }

    @SuppressWarnings("ClassExplicitlyAnnotation")
    static final class RankLiteral extends AnnotationLiteral<Rank> implements Rank {
        private final Grade grade;
        private final String label;
        private final String note;

        RankLiteral(Grade grade, String label, String note) {
            this.grade = grade;
            this.label = label;
            this.note = note;
        }

        @Override
        public Grade grade() {
            return grade;
        }

        @Override
        public String label() {
            return label;
        }

        @Override
        public String note() {
            return note;
        }
    }

    interface Card {
    }

    @Rank(grade = Grade.GOLD)
    @Dependent
    static class GoldCard implements Card {
    }

    @Test
    void equivalentQualifiersHashAlikeAndABindingMemberTellsThemApart() {
        try (ApplicationContext context = ApplicationContext.run()) {
            BeanManager manager = CDI.current().getBeanManager();
            Rank gold = new RankLiteral(Grade.GOLD, "", "first");
            Rank goldNoted = new RankLiteral(Grade.GOLD, "", "second");
            Rank silver = new RankLiteral(Grade.SILVER, "", "first");
            Rank goldLabelled = new RankLiteral(Grade.GOLD, "vip", "first");

            assertTrue(manager.areQualifiersEquivalent(gold, goldNoted));
            assertEquals(manager.getQualifierHashCode(gold), manager.getQualifierHashCode(goldNoted));

            assertFalse(manager.areQualifiersEquivalent(gold, silver));
            assertNotEquals(manager.getQualifierHashCode(gold), manager.getQualifierHashCode(silver));

            assertFalse(manager.areQualifiersEquivalent(gold, goldLabelled));
            assertNotEquals(manager.getQualifierHashCode(gold), manager.getQualifierHashCode(goldLabelled));

            assertEquals(1, manager.getBeans(Card.class, gold).size());
        }
    }
}
