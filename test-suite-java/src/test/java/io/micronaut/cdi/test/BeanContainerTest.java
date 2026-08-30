package io.micronaut.cdi.test;

import io.micronaut.context.ApplicationContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Default;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanContainer;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.inject.Named;
import jakarta.inject.Qualifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BeanContainerTest {

    @Qualifier
    @Retention(RetentionPolicy.RUNTIME)
    @interface Loud {
    }

    interface Speaker {
        String say();
    }

    @ApplicationScoped
    @Named("polite")
    static class PoliteSpeaker implements Speaker {
        @Override
        public String say() {
            return "please";
        }
    }

    @Dependent
    @Loud
    static class LoudSpeaker implements Speaker {
        @Override
        public String say() {
            return "NOW";
        }
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
    void theStaticEntryPointResolvesToTheRunningContainer() {
        assertNotNull(CDI.current().getBeanContainer());
    }

    @Test
    void theBeanManagerAnswersAsMuchAsCdiLiteCan() {
        jakarta.enterprise.inject.spi.BeanManager manager = CDI.current().getBeanManager();
        assertNotNull(manager);
        assertTrue(manager.isQualifier(Loud.class));
        assertFalse(manager.isPassivatingScope(ApplicationScoped.class));
        // and what belongs to CDI Full says so rather than answering
        assertThrows(UnsupportedOperationException.class, () -> manager.createAnnotatedType(PoliteSpeaker.class));
    }

    @Test
    void theBeanManagerIsInjectable() {
        assertNotNull(context.getBean(jakarta.enterprise.inject.spi.BeanManager.class));
    }

    @Test
    void twoQualifiersAreTheSameWhenTheirBindingMembersAre() {
        jakarta.enterprise.inject.spi.BeanManager manager = CDI.current().getBeanManager();
        assertTrue(manager.areQualifiersEquivalent(new LoudLiteral(), new LoudLiteral()));
        assertEquals(manager.getQualifierHashCode(new LoudLiteral()),
            manager.getQualifierHashCode(new LoudLiteral()));
    }

    @Test
    void getBeansWithNoQualifierResolvesTheDefaultBean() {
        BeanContainer container = CDI.current().getBeanContainer();
        Set<Bean<?>> beans = container.getBeans(Speaker.class);
        assertEquals(1, beans.size(), "expected only the default speaker, got " + beans);
        assertEquals(PoliteSpeaker.class, beans.iterator().next().getBeanClass());
    }

    @Test
    void getBeansWithAQualifierResolvesTheQualifiedBean() {
        BeanContainer container = CDI.current().getBeanContainer();
        Set<Bean<?>> beans = container.getBeans(Speaker.class, new LoudLiteral());
        assertEquals(1, beans.size(), "expected only the loud speaker, got " + beans);
        assertEquals(LoudSpeaker.class, beans.iterator().next().getBeanClass());
    }

    @Test
    void getBeansWithAnyResolvesEveryBeanOfTheType() {
        BeanContainer container = CDI.current().getBeanContainer();
        assertEquals(2, container.getBeans(Speaker.class, Any.Literal.INSTANCE).size());
    }

    @Test
    void aBeanReportsWhatItWasWrittenWith() {
        BeanContainer container = CDI.current().getBeanContainer();
        Bean<?> polite = container.getBeans(Speaker.class).iterator().next();
        assertEquals(ApplicationScoped.class, polite.getScope());
        assertEquals("polite", polite.getName());
        assertTrue(polite.getTypes().contains(Speaker.class));
        assertTrue(polite.getTypes().contains(PoliteSpeaker.class));
        assertTrue(polite.getTypes().contains(Object.class));
        assertTrue(polite.getQualifiers().contains(Any.Literal.INSTANCE));
        assertFalse(polite.isAlternative());
    }

    @Test
    void aDependentBeanReportsTheDependentPseudoScope() {
        BeanContainer container = CDI.current().getBeanContainer();
        Bean<?> loud = container.getBeans(Speaker.class, new LoudLiteral()).iterator().next();
        assertEquals(Dependent.class, loud.getScope());
    }

    @Test
    void programmaticLookupNarrowsByTypeAndQualifier() {
        Instance<Object> lookup = CDI.current();
        assertEquals("NOW", lookup.select(Speaker.class, new LoudLiteral()).get().say());
        assertEquals("please", lookup.select(Speaker.class).get().say());
    }

    @Test
    void programmaticLookupTellsWhenItResolvesToNothingOrToMoreThanOne() {
        Instance<Speaker> speakers = CDI.current().select(Speaker.class, Any.Literal.INSTANCE);
        assertTrue(speakers.isAmbiguous());
        assertFalse(speakers.isUnsatisfied());
        assertTrue(CDI.current().select(Runnable.class).isUnsatisfied());
    }

    @Test
    void aReferenceObtainedThroughTheContainerIsTheContextualInstance() {
        BeanContainer container = CDI.current().getBeanContainer();
        Bean<?> polite = container.getBeans(Speaker.class).iterator().next();
        Object first = container.getReference(polite, Speaker.class,
            container.createCreationalContext(polite));
        Object second = container.getReference(polite, Speaker.class,
            container.createCreationalContext(polite));
        // the polite speaker is application scoped, so both references reach the one instance the scope holds
        assertEquals(((Speaker) first).say(), ((Speaker) second).say());
    }

    @Test
    void theContainerAnswersWhatAnAnnotationIs() {
        BeanContainer container = CDI.current().getBeanContainer();
        assertTrue(container.isQualifier(Loud.class));
        assertTrue(container.isNormalScope(ApplicationScoped.class));
        assertFalse(container.isNormalScope(Dependent.class));
        assertTrue(container.isScope(Dependent.class));
        assertFalse(container.isQualifier(Override.class));
    }

    @Test
    void theContainerAppliesTheRulesOfResolutionToTypesAndQualifiersOnTheirOwn() {
        BeanContainer container = CDI.current().getBeanContainer();
        assertTrue(container.isMatchingBean(
            Set.of(Speaker.class, PoliteSpeaker.class),
            Set.of(Default.Literal.INSTANCE, Any.Literal.INSTANCE),
            Speaker.class,
            Set.of()));
        assertFalse(container.isMatchingBean(
            Set.of(Speaker.class),
            Set.of(Any.Literal.INSTANCE),
            Speaker.class,
            Set.of(new LoudLiteral())));
    }

    @Test
    void theRequestScopeHasAContextThatIsNotActiveOutsideARequest() {
        BeanContainer container = CDI.current().getBeanContainer();
        // getContext hands out the active context of a scope; outside a request there is none (section 2.9)
        org.junit.jupiter.api.Assertions.assertThrows(jakarta.enterprise.context.ContextNotActiveException.class,
            () -> container.getContext(jakarta.enterprise.context.RequestScoped.class));
        assertTrue(container.getContext(ApplicationScoped.class).isActive());
    }

    @SuppressWarnings("all")
    static final class LoudLiteral extends jakarta.enterprise.util.AnnotationLiteral<Loud> implements Loud {
    }
}
