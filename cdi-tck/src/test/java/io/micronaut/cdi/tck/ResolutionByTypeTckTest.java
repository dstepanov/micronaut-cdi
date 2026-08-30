package io.micronaut.cdi.tck;

import io.micronaut.context.ApplicationContext;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanContainer;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.enterprise.util.TypeLiteral;
import org.jboss.cdi.tck.tests.lookup.typesafe.resolution.Animal;
import org.jboss.cdi.tck.tests.lookup.typesafe.resolution.ChunkyLiteral;
import org.jboss.cdi.tck.tests.lookup.typesafe.resolution.Cod;
import org.jboss.cdi.tck.tests.lookup.typesafe.resolution.Expensive;
import org.jboss.cdi.tck.tests.lookup.typesafe.resolution.Farmer;
import org.jboss.cdi.tck.tests.lookup.typesafe.resolution.Halibut;
import org.jboss.cdi.tck.tests.lookup.typesafe.resolution.Max;
import org.jboss.cdi.tck.tests.lookup.typesafe.resolution.Min;
import org.jboss.cdi.tck.tests.lookup.typesafe.resolution.NumberProducer;
import org.jboss.cdi.tck.tests.lookup.typesafe.resolution.ScottishFish;
import org.jboss.cdi.tck.tests.lookup.typesafe.resolution.ScottishFishFarmer;
import org.jboss.cdi.tck.tests.lookup.typesafe.resolution.Sole;
import org.jboss.cdi.tck.tests.lookup.typesafe.resolution.Spider;
import org.jboss.cdi.tck.tests.lookup.typesafe.resolution.Tuna;
import org.jboss.cdi.tck.tests.lookup.typesafe.resolution.Whitefish;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code org.jboss.cdi.tck.tests.lookup.typesafe.resolution.ResolutionByTypeTest}, run against the kit's own
 * scenarios: the typesafe resolution of section 2.4.2.
 */
class ResolutionByTypeTckTest {

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
     * The kit's own literal for the {@code Expensive} qualifier is not public, so the same literal is written
     * here: the {@code cost} member is annotated {@code Nonbinding} and takes no part in the comparison.
     */
    @SuppressWarnings("all")
    private static final class ExpensiveLiteral
        extends jakarta.enterprise.util.AnnotationLiteral<Expensive> implements Expensive {

        private final boolean veryExpensive;
        private final int cost;

        private ExpensiveLiteral(boolean veryExpensive, int cost) {
            this.veryExpensive = veryExpensive;
            this.cost = cost;
        }

        @Override
        public boolean veryExpensive() {
            return veryExpensive;
        }

        @Override
        public int cost() {
            return cost;
        }
    }

    private static Set<Type> typesOf(Set<Bean<?>> beans) {
        Set<Type> types = new HashSet<>();
        beans.forEach(bean -> types.addAll(bean.getTypes()));
        return types;
    }

    /**
     * {@code testDefaultBindingTypeAssumed}: a lookup that names no qualifier is looking for the default one.
     */
    @Test
    void aLookupThatNamesNoQualifierAssumesTheDefaultOne() {
        Set<Bean<?>> beans = container.getBeans(Tuna.class);
        assertEquals(1, beans.size(), "expected one tuna, got " + beans);
        assertTrue(beans.iterator().next().getTypes().contains(Tuna.class));
    }

    /**
     * {@code testAllQualifiersSpecifiedForResolutionMustAppearOnBean}: every qualifier named by a lookup has to
     * be on the bean, and a bean carrying more than the ones named still qualifies.
     */
    @Test
    void everyQualifierNamedHasToBeOnTheBean() {
        Set<Bean<?>> chunkyWhitefish = container.getBeans(Animal.class, new ChunkyLiteral(),
            new Whitefish.Literal());
        assertEquals(1, chunkyWhitefish.size(), "expected the cod, got " + chunkyWhitefish);
        assertTrue(typesOf(chunkyWhitefish).contains(Cod.class));

        Set<Bean<?>> whitefish = container.getBeans(ScottishFish.class, new Whitefish.Literal());
        assertEquals(2, whitefish.size(), "expected the cod and the sole, got " + whitefish);
        Set<Type> types = typesOf(whitefish);
        assertTrue(types.contains(Cod.class) && types.contains(Sole.class), "got " + types);
    }

    /**
     * {@code testResolveByTypeWithTypeParameter}: a lookup of a parameterized type resolves the bean of that
     * parameterization.
     */
    @Test
    void aLookupOfAParameterizedTypeResolvesThatParameterization() {
        Set<Bean<?>> beans = container.getBeans(new TypeLiteral<Farmer<ScottishFish>>() {
        }.getType());
        assertEquals(1, beans.size(), "expected the scottish fish farmer, got " + beans);
        assertTrue(beans.iterator().next().getTypes().contains(ScottishFishFarmer.class));
    }

    /**
     * {@code testResolveByTypeWithArray}: a lookup of an array type resolves the bean of that array.
     */
    @Test
    void aLookupOfAnArrayTypeResolvesTheBeanOfThatArray() {
        assertEquals(1, container.getBeans(Spider[].class).size());
    }

    /**
     * {@code testResolveByTypeWithPrimitives}: a primitive and the class that boxes it are the same bean type,
     * so a lookup of either resolves the beans of both.
     */
    @Test
    void aPrimitiveAndItsBoxedClassAreTheSameBeanType() {
        // the minimum is produced by a field of the primitive type and the maximum by a method of the class
        // that boxes it, and a lookup of either type resolves both
        assertEquals(2, container.getBeans(Double.class,
            new org.jboss.cdi.tck.tests.lookup.typesafe.resolution.Number.Literal()).size());
        assertEquals(2, container.getBeans(double.class,
            new org.jboss.cdi.tck.tests.lookup.typesafe.resolution.Number.Literal()).size());
        assertEquals(NumberProducer.min, CDI.current().select(Double.class, new Min.Literal()).get());
        assertEquals(NumberProducer.max, CDI.current().select(Double.class, new Max.Literal()).get());
    }

    /**
     * {@code testResolveByTypeWithNonBindingMembers}: a member of a qualifier annotated {@code Nonbinding} takes
     * no part in the comparison, so beans differing only in it all qualify.
     */
    @Test
    void aNonBindingMemberTakesNoPartInTheComparison() {
        Set<Bean<?>> beans = container.getBeans(Animal.class, new ExpensiveLiteral(true, 60),
            new Whitefish.Literal());
        assertEquals(2, beans.size(), "expected the two expensive whitefish, got " + beans);
        assertTrue(typesOf(beans).contains(Halibut.class), "got " + typesOf(beans));
    }
}
