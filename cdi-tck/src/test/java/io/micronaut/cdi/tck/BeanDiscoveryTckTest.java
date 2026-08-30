package io.micronaut.cdi.tck;

import io.micronaut.context.ApplicationContext;
import jakarta.enterprise.inject.spi.BeanContainer;
import jakarta.enterprise.inject.spi.CDI;
import org.jboss.cdi.tck.tests.alternative.resolution.qualifier.Dungeon;
import org.jboss.cdi.tck.tests.alternative.resolution.qualifier.Forest;
import org.jboss.cdi.tck.tests.alternative.resolution.qualifier.Larch;
import org.jboss.cdi.tck.tests.alternative.resolution.qualifier.Monster;
import org.jboss.cdi.tck.tests.alternative.resolution.qualifier.Tree;
import org.jboss.cdi.tck.tests.alternative.resolution.qualifier.Baz;
import org.jboss.cdi.tck.tests.alternative.resolution.qualifier.FalseLiteral;
import org.jboss.cdi.tck.tests.alternative.resolution.qualifier.Foo;
import org.jboss.cdi.tck.tests.alternative.resolution.qualifier.Qux;
import org.jboss.cdi.tck.tests.alternative.resolution.qualifier.TrueLiteral;
import org.jboss.cdi.tck.tests.vetoed.Elephant;
import org.jboss.cdi.tck.tests.vetoed.Shark;
import org.jboss.cdi.tck.tests.vetoed.aquarium.Fishy;
import org.jboss.cdi.tck.tests.vetoed.aquarium.Piranha;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code org.jboss.cdi.tck.tests.vetoed.VetoedTest} and
 * {@code org.jboss.cdi.tck.tests.alternative.resolution.qualifier.AlternativeResolutionByQualifierTest}, run
 * against the kit's own scenarios.
 *
 * <p>Both are about which classes are beans and which bean an injection point resolves to, which is decided here
 * while the classes are compiled rather than as a deployment is assembled.</p>
 */
class BeanDiscoveryTckTest {

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

    /**
     * {@code VetoedTest#testVetoedClass}: a class annotated {@code Vetoed} is not a bean.
     */
    @Test
    void aVetoedClassIsNotABean() {
        assertTrue(container.getBeans(Elephant.class).isEmpty(),
            "the vetoed elephant should not be a bean");
    }

    /**
     * {@code VetoedTest#testVetoedPackage}: every class in a package annotated {@code Vetoed} is not a bean.
     */
    @Test
    void aClassInAVetoedPackageIsNotABean() {
        assertTrue(container.getBeans(Piranha.class).isEmpty(),
            "the piranha is in a vetoed package and should not be a bean");
    }

    /**
     * A class that is neither vetoed nor in a vetoed package is still a bean, whatever its neighbours are.
     */
    @Test
    void aClassBesideAVetoedOneIsStillABean() {
        assertFalse(container.getBeans(Shark.class, new Fishy.Literal()).isEmpty(),
            "the shark is not vetoed and should be a bean");
    }

    /**
     * {@code QualifierInheritedTest#testResolution}: the alternative extends a bean whose qualifier is annotated
     * {@code Inherited}, so the alternative carries that qualifier too and is an alternative to it. Resolving the
     * supertype by that qualifier gives the alternative.
     */
    @Test
    void anAlternativeInheritsAQualifierThatIsInheritedAndReplacesByIt() {
        assertEquals(Larch.class,
            container.resolve(container.getBeans(Tree.class, TrueLiteral.INSTANCE)).getBeanClass());
        assertEquals(0, context.getBean(Forest.class).getTree().ping());
    }

    /**
     * {@code QualifierNotInheritedTest#testResolution}: the qualifier of the bean the alternative extends is not
     * annotated {@code Inherited}, so the alternative does not carry it and is not an alternative to it.
     * Resolving the supertype by that qualifier gives the bean rather than the alternative.
     */
    @Test
    void anAlternativeDoesNotInheritAQualifierThatIsNotInherited() {
        assertEquals(Monster.class,
            container.resolve(container.getBeans(Monster.class, FalseLiteral.INSTANCE)).getBeanClass());
        assertEquals(1, context.getBean(Dungeon.class).getMonster().ping());
    }

    /**
     * {@code QualifierNotDeclaredTest#testResolution}: the alternative declares no qualifier, so it has the
     * default one and is an alternative only to the beans that also have it. The bean that declares a qualifier
     * of its own is not replaced, and is what an injection point asking for that qualifier resolves to.
     */
    @Test
    void anAlternativeWithTheDefaultQualifierDoesNotReplaceAQualifiedBean() {
        assertEquals(Baz.class,
            container.resolve(container.getBeans(Foo.class, TrueLiteral.INSTANCE)).getBeanClass());
        assertEquals(1, context.getBean(Qux.class).getFoo().ping());
    }
}
