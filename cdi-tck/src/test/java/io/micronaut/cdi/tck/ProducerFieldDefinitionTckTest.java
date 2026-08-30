package io.micronaut.cdi.tck;

import io.micronaut.context.ApplicationContext;
import io.micronaut.inject.qualifiers.Qualifiers;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Default;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanContainer;
import jakarta.enterprise.inject.spi.CDI;
import org.jboss.cdi.tck.tests.implementation.producer.field.definition.Animal;
import org.jboss.cdi.tck.tests.implementation.producer.field.definition.AsAnimal;
import org.jboss.cdi.tck.tests.implementation.producer.field.definition.BlackWidow;
import org.jboss.cdi.tck.tests.implementation.producer.field.definition.DeadlyAnimal;
import org.jboss.cdi.tck.tests.implementation.producer.field.definition.DeadlySpider;
import org.jboss.cdi.tck.tests.implementation.producer.field.definition.FunnelWeaverSpiderConsumer;
import org.jboss.cdi.tck.tests.implementation.producer.field.definition.FunnelWeaverSpiderProducer;
import org.jboss.cdi.tck.tests.implementation.producer.field.definition.OtherSpiderProducer;
import org.jboss.cdi.tck.tests.implementation.producer.field.definition.Pet;
import org.jboss.cdi.tck.tests.implementation.producer.field.definition.Spider;
import org.jboss.cdi.tck.tests.implementation.producer.field.definition.Tame;
import org.jboss.cdi.tck.tests.implementation.producer.field.definition.Tarantula;
import org.jboss.cdi.tck.tests.implementation.producer.field.definition.WolfSpider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code org.jboss.cdi.tck.tests.implementation.producer.field.definition.ProducerFieldDefinitionTest}, run
 * against the kit's own scenarios.
 */
class ProducerFieldDefinitionTckTest {

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
     * {@code testParameterizedReturnType}: a producer field of a parameterized type produces a bean of that
     * parameterization, and is what an injection point of it resolves to.
     */
    @Test
    void aProducerFieldOfAParameterizedTypeProducesThatParameterization() {
        FunnelWeaverSpiderConsumer consumer = context.getBean(FunnelWeaverSpiderConsumer.class);
        assertNotNull(consumer.getInjectedSpider());
        assertSame(FunnelWeaverSpiderProducer.getSpider(), consumer.getInjectedSpider());
    }

    /**
     * {@code testBeanDeclaresMultipleProducerFields}: a class may declare more than one producer field, and each
     * produces the bean its own qualifier resolves.
     */
    @Test
    void aClassMayDeclareMoreThanOneProducerField() {
        assertSame(OtherSpiderProducer.WOLF_SPIDER,
            context.getBean(WolfSpider.class, Qualifiers.byAnnotation(new Pet.Literal())));
        assertSame(OtherSpiderProducer.BLACK_WIDOW,
            context.getBean(BlackWidow.class, Qualifiers.byAnnotation(new Tame.Literal())));
    }

    /**
     * {@code testApiTypeForClassReturn}: the bean types of what a producer field produces are the whole
     * hierarchy of its type.
     */
    @Test
    void theBeanTypesOfAProducedClassAreItsWholeHierarchy() {
        Bean<?> tarantula = container.getBeans(Tarantula.class, new Pet.Literal()).iterator().next();
        assertEquals(Set.of(Tarantula.class, DeadlySpider.class, Spider.class, Animal.class, DeadlyAnimal.class,
            Object.class), tarantula.getTypes());
    }

    /**
     * {@code testApiTypeForInterfaceReturn}: a producer field of an interface type produces a bean of that
     * interface and of {@code Object}.
     */
    @Test
    void theBeanTypesOfAProducedInterfaceAreItAndObject() {
        Bean<?> animal = container.getBeans(Animal.class, new AsAnimal.Literal()).iterator().next();
        assertEquals(Set.of(Animal.class, Object.class), animal.getTypes());
    }

    /**
     * {@code testApiTypeForPrimitiveReturn}: a producer field of a primitive type produces a bean of that
     * primitive and of {@code Object}, resolved here by the name it was given.
     */
    @Test
    void theBeanTypesOfAProducedPrimitiveAreItAndObject() {
        Set<Bean<?>> named = container.getBeans("SpiderSize");
        assertEquals(1, named.size(), "expected the named spider size, got " + named);
        assertEquals(Set.of(int.class, Object.class), named.iterator().next().getTypes());
    }

    /**
     * {@code testApiTypeForArrayTypeReturn}: a producer field of an array type produces a bean of that array
     * type and of {@code Object}, and of nothing else.
     */
    @Test
    void theBeanTypesOfAProducedArrayAreItAndObject() {
        Bean<?> spiders = container.getBeans(Spider[].class, Any.Literal.INSTANCE).iterator().next();
        assertEquals(Set.of(Spider[].class, Object.class), spiders.getTypes());
    }

    /**
     * {@code testBindingType}: a producer field that declares a qualifier produces a bean carrying it, along
     * with {@code Any}.
     */
    @Test
    void aProducerFieldThatDeclaresAQualifierProducesABeanCarryingIt() {
        Bean<?> tarantula = container.getBeans(Tarantula.class, new Tame.Literal()).iterator().next();
        assertTrue(tarantula.getQualifiers().contains(new Tame.Literal()),
            "expected the Tame qualifier, got " + tarantula.getQualifiers());
        assertTrue(tarantula.getQualifiers().contains(Any.Literal.INSTANCE));
    }

    /**
     * {@code testDefaultBindingType}: a producer field that declares no qualifier produces a bean with the
     * default qualifier.
     */
    @Test
    void aProducerFieldThatDeclaresNoQualifierProducesADefaultBean() {
        Bean<?> spiders = container.getBeans(Spider[].class).iterator().next();
        assertTrue(spiders.getQualifiers().contains(Default.Literal.INSTANCE),
            "expected the Default qualifier, got " + spiders.getQualifiers());
    }
}
