package io.micronaut.cdi.test;

import io.micronaut.context.ApplicationContext;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.inject.Stereotype;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ScopeInheritanceTest {

    @Stereotype
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    @interface Plain {
    }

    @jakarta.enterprise.context.NormalScope
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    @interface OwnScope {
    }

    @Plain
    static class Order {
    }

    @RequestScoped
    static class Dog {
    }

    @Plain
    static class BorderCollie extends Dog {
    }

    @OwnScope
    static class Horse {
    }

    @Plain
    static class ShetlandPony extends Horse {
    }

    @Stereotype
    @RequestScoped
    @java.lang.annotation.Inherited
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    @interface InheritableAnimal {
    }

    @Stereotype
    @RequestScoped
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    @interface PlainAnimal {
    }

    // the scope comes through the stereotype the class declares itself
    @InheritableAnimal
    static class LongHairedDog {
    }

    // ... is inherited, because the stereotype is marked Inherited
    @Plain
    static class InheritableCollie extends LongHairedDog {
    }

    // ... and indirectly inherited, a level further down
    @Plain
    static class EnglishCollie extends InheritableCollie {
    }

    @PlainAnimal
    static class PlainHorse {
    }

    // ... but not inherited where the stereotype is not marked Inherited, even though the scope it carries is
    @Plain
    static class PlainPony extends PlainHorse {
    }

    @Test
    void aScopeCarriedByADeclaredStereotypeIsTheScope() {
        try (ApplicationContext context = ApplicationContext.run()) {
            BeanManager manager = context.getBean(BeanManager.class);
            assertEquals(RequestScoped.class,
                manager.getBeans(LongHairedDog.class).iterator().next().getScope());
        }
    }

    @Test
    void anInheritedStereotypeCarriesItsScopeDown() {
        try (ApplicationContext context = ApplicationContext.run()) {
            BeanManager manager = context.getBean(BeanManager.class);
            assertEquals(RequestScoped.class,
                manager.getBeans(InheritableCollie.class).iterator().next().getScope());
            assertEquals(RequestScoped.class,
                manager.getBeans(EnglishCollie.class).iterator().next().getScope());
        }
    }

    @Test
    void aStereotypeNotMarkedInheritedDoesNotCarryItsScopeDown() {
        try (ApplicationContext context = ApplicationContext.run()) {
            BeanManager manager = context.getBean(BeanManager.class);
            assertEquals(Dependent.class,
                manager.getBeans(PlainPony.class).iterator().next().getScope());
        }
    }

    @Test
    void aBareStereotypeLeavesTheBeanDependent() {
        try (ApplicationContext context = ApplicationContext.run()) {
            BeanManager manager = context.getBean(BeanManager.class);
            Set<Bean<?>> beans = manager.getBeans(Order.class);
            assertEquals(1, beans.size(), "expected the stereotyped order to be a bean");
            assertEquals(Dependent.class, beans.iterator().next().getScope());
        }
    }

    @Test
    void anInheritableScopeIsInherited() {
        try (ApplicationContext context = ApplicationContext.run()) {
            BeanManager manager = context.getBean(BeanManager.class);
            assertEquals(RequestScoped.class,
                manager.getBeans(BorderCollie.class).iterator().next().getScope());
        }
    }

    @Test
    void aScopeNotMarkedInheritedIsNotInherited() {
        try (ApplicationContext context = ApplicationContext.run()) {
            BeanManager manager = context.getBean(BeanManager.class);
            Set<Bean<?>> beans = manager.getBeans(ShetlandPony.class);
            assertEquals(1, beans.size(), "expected the pony to be a bean");
            assertEquals(Dependent.class, beans.iterator().next().getScope());
        }
    }
}
