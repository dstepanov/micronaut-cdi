package io.micronaut.cdi.tck;

import io.micronaut.context.ApplicationContext;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Default;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanContainer;
import jakarta.enterprise.inject.spi.CDI;
import org.jboss.cdi.tck.tests.implementation.producer.method.definition.Animal;
import org.jboss.cdi.tck.tests.implementation.producer.method.definition.BeanWithStaticProducerMethod;
import org.jboss.cdi.tck.tests.implementation.producer.method.definition.Bite;
import org.jboss.cdi.tck.tests.implementation.producer.method.definition.Cherry;
import org.jboss.cdi.tck.tests.implementation.producer.method.definition.DeadlyAnimal;
import org.jboss.cdi.tck.tests.implementation.producer.method.definition.DeadlySpider;
import org.jboss.cdi.tck.tests.implementation.producer.method.definition.Spider;
import org.jboss.cdi.tck.tests.implementation.producer.method.definition.Tarantula;
import org.jboss.cdi.tck.tests.implementation.producer.method.definition.Tame;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code org.jboss.cdi.tck.tests.implementation.producer.method.definition.ProducerMethodDefinitionTest}, run
 * against the kit's own scenarios.
 */
class ProducerMethodDefinitionTckTest {

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
     * {@code testStaticMethod}: a producer method may be static, and produces what it returns.
     */
    @Test
    void aProducerMethodMayBeStatic() {
        assertEquals(1, container.getBeans(String.class, new Tame.Literal()).size());
        assertEquals(BeanWithStaticProducerMethod.getString(),
            context.getBean(String.class,
                io.micronaut.inject.qualifiers.Qualifiers.byAnnotation(new Tame.Literal())));
    }

    /**
     * {@code testProducerOnNonBean}: a producer declared by a class that is not a bean produces nothing. The
     * class declares only a constructor that takes a parameter and is not annotated {@code Inject}, so it is not
     * a managed bean.
     */
    @Test
    void aProducerOnAClassThatIsNotABeanProducesNothing() {
        assertTrue(container.getBeans(Cherry.class, Any.Literal.INSTANCE).isEmpty());
    }

    /**
     * {@code testDefaultBindingType}: a producer that declares no qualifier produces a bean with the default
     * qualifier and {@code Any}.
     */
    @Test
    void aProducerThatDeclaresNoQualifierProducesADefaultBean() {
        Set<Bean<?>> beans = container.getBeans(Tarantula.class);
        assertEquals(1, beans.size());
        Set<java.lang.annotation.Annotation> qualifiers = beans.iterator().next().getQualifiers();
        assertEquals(2, qualifiers.size(), "expected Default and Any, got " + qualifiers);
        assertTrue(qualifiers.contains(Default.Literal.INSTANCE));
        assertTrue(qualifiers.contains(Any.Literal.INSTANCE));
    }

    /**
     * {@code testApiTypeForClassReturn}: the bean types of what a producer produces are the whole hierarchy of
     * the type it returns.
     */
    @Test
    void theBeanTypesOfAProducedClassAreItsWholeHierarchy() {
        Bean<?> tarantula = container.getBeans(Tarantula.class).iterator().next();
        assertEquals(Set.of(Tarantula.class, DeadlySpider.class, Spider.class, Animal.class, DeadlyAnimal.class,
            Object.class), tarantula.getTypes());
    }

    /**
     * {@code testApiTypeForInterfaceReturn}: a producer returning an interface produces a bean of that interface
     * and of {@code Object}.
     */
    @Test
    void theBeanTypesOfAProducedInterfaceAreItAndObject() {
        Bean<?> bite = container.getBeans(Bite.class).iterator().next();
        assertEquals(Set.of(Bite.class, Object.class), bite.getTypes());
    }

    /**
     * {@code testApiTypeForArrayTypeReturn}: a producer returning an array produces a bean of that array type and
     * of {@code Object}, and of nothing else.
     */
    @Test
    void theBeanTypesOfAProducedArrayAreItAndObject() {
        Bean<?> spiders = container.getBeans(Spider[].class, Any.Literal.INSTANCE).iterator().next();
        assertEquals(Set.of(Spider[].class, Object.class), spiders.getTypes());
    }
}
