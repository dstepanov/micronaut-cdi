package io.micronaut.cdi.tck;

import io.micronaut.context.ApplicationContext;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Default;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanContainer;
import jakarta.enterprise.inject.spi.CDI;
import org.jboss.cdi.tck.tests.definition.qualifier.BorderCollie;
import org.jboss.cdi.tck.tests.definition.qualifier.Cat;
import org.jboss.cdi.tck.tests.definition.qualifier.ChunkyQualifier;
import org.jboss.cdi.tck.tests.definition.qualifier.ClippedBorderCollie;
import org.jboss.cdi.tck.tests.definition.qualifier.Cod;
import org.jboss.cdi.tck.tests.definition.qualifier.DefangedTarantula;
import org.jboss.cdi.tck.tests.definition.qualifier.EnglishBorderCollie;
import org.jboss.cdi.tck.tests.definition.qualifier.HairyQualifier;
import org.jboss.cdi.tck.tests.definition.qualifier.MiniatureShetlandPony;
import org.jboss.cdi.tck.tests.definition.qualifier.ShetlandPony;
import org.jboss.cdi.tck.tests.definition.qualifier.SynchronousQualifier;
import org.jboss.cdi.tck.tests.definition.qualifier.TameLiteral;
import org.jboss.cdi.tck.tests.definition.qualifier.Tarantula;
import org.jboss.cdi.tck.tests.definition.qualifier.WhitefishQualifier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code org.jboss.cdi.tck.tests.definition.qualifier.QualifierDefinitionTest}, run against the kit's own
 * scenarios through the container of this module.
 *
 * <p>The kit's test class drives a container through Arquillian and cannot run here: it builds a deployment
 * archive out of its package and deploys it, and a bean of this implementation is a bean by the time it has been
 * compiled, with nothing to deploy. Its assertions can run, and each method below is the assertion of the kit
 * method it is named after, made through the {@code BeanContainer} of section 2.9.1.</p>
 */
class QualifierDefinitionTckTest {

    private static ApplicationContext context;
    private static BeanContainer container;

    @BeforeAll
    static void startContainer() {
        context = ApplicationContext.run();
        container = CDI.current().getBeanContainer();
    }

    @AfterAll
    static void stopContainer() {
        context.close();
    }

    private static Set<Bean<?>> getBeans(Class<?> type, Annotation... qualifiers) {
        return container.getBeans(type, qualifiers);
    }

    private static Set<Annotation> qualifiersOf(Class<?> type, Annotation... qualifiers) {
        return getBeans(type, qualifiers).iterator().next().getQualifiers();
    }

    /**
     * {@code testQualifierDeclaresBindingAnnotation}.
     */
    @Test
    void qualifierDeclaresBindingAnnotation() {
        assertFalse(getBeans(Tarantula.class, new TameLiteral()).isEmpty());
    }

    /**
     * {@code testQualifiersDeclaredInJava}.
     */
    @Test
    void qualifiersDeclaredInJava() {
        Set<Annotation> qualifiers = qualifiersOf(Cat.class, new SynchronousQualifier());
        assertEquals(2, qualifiers.size(), "expected Any and Synchronous, got " + qualifiers);
        assertTrue(qualifiers.contains(new SynchronousQualifier()));
    }

    /**
     * {@code testMultipleQualifiers}.
     */
    @Test
    void multipleQualifiers() {
        Set<Annotation> qualifiers = qualifiersOf(Cod.class, new ChunkyQualifier(true), new WhitefishQualifier());
        assertEquals(4, qualifiers.size(), "expected Any, Whitefish, Chunky and Named, got " + qualifiers);
    }

    /**
     * {@code testFieldInjectedFromProducerMethod}: the producer method qualified {@code Tame} produces the
     * defanged tarantula, and is what a {@code Tame} injection point resolves to.
     */
    @Test
    void fieldInjectedFromProducerMethod() {
        assertInstanceOf(DefangedTarantula.class,
            context.getBean(Tarantula.class, io.micronaut.inject.qualifiers.Qualifiers.byAnnotation(new TameLiteral())));
    }

    /**
     * {@code testQualifierDeclaredInheritedIsInherited}.
     */
    @Test
    void qualifierDeclaredInheritedIsInherited() {
        Set<Annotation> qualifiers = qualifiersOf(BorderCollie.class, new HairyQualifier(false));
        assertEquals(2, qualifiers.size(), "expected Any and Hairy, got " + qualifiers);
        assertTrue(qualifiers.contains(new HairyQualifier(false)));
        assertTrue(qualifiers.contains(Any.Literal.INSTANCE));
    }

    /**
     * {@code testQualifierNotDeclaredInheritedIsNotInherited}: the qualifier of {@code Horse} is not annotated
     * {@code Inherited}, so the subclass has the default qualifier instead of it.
     */
    @Test
    void qualifierNotDeclaredInheritedIsNotInherited() {
        Set<Annotation> qualifiers = qualifiersOf(ShetlandPony.class);
        assertEquals(2, qualifiers.size(), "expected Any and Default, got " + qualifiers);
        assertTrue(qualifiers.contains(Default.Literal.INSTANCE));
        assertTrue(qualifiers.contains(Any.Literal.INSTANCE));
    }

    /**
     * {@code testQualifierNotDeclaredInheritedIsNotIndirectlyInherited}.
     */
    @Test
    void qualifierNotDeclaredInheritedIsNotIndirectlyInherited() {
        Set<Annotation> qualifiers = qualifiersOf(MiniatureShetlandPony.class);
        assertEquals(2, qualifiers.size(), "expected Any and Default, got " + qualifiers);
        assertTrue(qualifiers.contains(Default.Literal.INSTANCE));
    }

    /**
     * {@code testQualifierDeclaredInheritedIsBlockedByIntermediateClass}.
     */
    @Test
    void qualifierDeclaredInheritedIsBlockedByIntermediateClass() {
        Set<Annotation> qualifiers = qualifiersOf(ClippedBorderCollie.class, new HairyQualifier(true));
        assertEquals(2, qualifiers.size(), "expected Any and Hairy, got " + qualifiers);
        assertTrue(qualifiers.contains(new HairyQualifier(true)));
        assertTrue(qualifiers.contains(Any.Literal.INSTANCE));
    }

    /**
     * {@code testQualifierDeclaredInheritedIsIndirectlyInherited}.
     */
    @Test
    void qualifierDeclaredInheritedIsIndirectlyInherited() {
        Set<Annotation> qualifiers = qualifiersOf(EnglishBorderCollie.class, new HairyQualifier(false));
        assertEquals(2, qualifiers.size(), "expected Any and Hairy, got " + qualifiers);
        assertTrue(qualifiers.contains(new HairyQualifier(false)));
    }

    /**
     * A member of a qualifier takes part in the comparison, so the same qualifier with another value resolves
     * nothing.
     */
    @Test
    void aQualifierMemberIsPartOfTheComparison() {
        assertTrue(getBeans(Cod.class, new ChunkyQualifier(false)).isEmpty());
    }
}
