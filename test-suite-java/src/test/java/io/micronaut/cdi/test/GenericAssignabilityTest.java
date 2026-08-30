package io.micronaut.cdi.test;

import io.micronaut.context.ApplicationContext;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.enterprise.util.TypeLiteral;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GenericAssignabilityTest {

    interface Result<T1, T2> {
    }

    @Dependent
    static class ResultImpl<T1 extends Exception, T2 extends Exception> implements Result<T1, T2> {
    }

    @SuppressWarnings("unused")
    private static <T1 extends RuntimeException, T2 extends T1, T3> void typeHolder() {
    }

    @Dependent
    static class Dao<T1, T2> {
    }

    @Dependent
    static class ObjectDao extends Dao<Object, Object> {
    }

    @Dependent
    static class DaoProducer {
        @jakarta.enterprise.inject.Produces
        public Dao<Object, Object> getDao() {
            return new Dao<>();
        }

        @SuppressWarnings({"rawtypes"})
        @jakarta.enterprise.inject.Produces
        Dao getRawDao() {
            return new Dao();
        }
    }

    @Test
    void typeVariablePairsAgainstDao() throws Exception {
        java.lang.reflect.TypeVariable<?>[] vars =
            GenericAssignabilityTest.class.getDeclaredMethod("typeHolder").getTypeParameters();
        try (ApplicationContext context = ApplicationContext.run()) {
            BeanManager manager = context.getBean(BeanManager.class);
            java.lang.reflect.Type dao = io.micronaut.cdi.runtime.CdiParameterizedType.of(
                Dao.class, new java.lang.reflect.Type[]{vars[0], vars[2]});
            Set<Bean<?>> beans = manager.getBeans(dao);
            for (Bean<?> bean : beans) {
                System.out.println("DBG dao matched " + bean + " types=" + bean.getTypes());
            }
            assertEquals(1, beans.size(), "only the raw producer's Dao says nothing about its parameters");
        }
    }

    @Test
    void wildcardWithLowerBoundBeyondTheVariableBoundDoesNotMatch() {
        try (ApplicationContext context = ApplicationContext.run()) {
            BeanManager manager = context.getBean(BeanManager.class);
            Set<Bean<?>> beans = manager.getBeans(
                new TypeLiteral<Result<? extends Exception, ? super Throwable>>() { }.getType());
            for (Bean<?> bean : beans) {
                System.out.println("DBG matched " + bean + " types=" + bean.getTypes());
            }
            assertEquals(0, beans.size());
        }
    }

    @Test
    void wildcardsWithinTheVariableBoundsMatch() {
        try (ApplicationContext context = ApplicationContext.run()) {
            BeanManager manager = context.getBean(BeanManager.class);
            assertEquals(1, manager.getBeans(
                new TypeLiteral<Result<? extends Throwable, ? super Exception>>() { }.getType()).size());
        }
    }

    @Test
    void typeVariablesWithinTheVariableBoundsMatch() throws Exception {
        java.lang.reflect.TypeVariable<?>[] vars =
            GenericAssignabilityTest.class.getDeclaredMethod("typeHolder").getTypeParameters();
        try (ApplicationContext context = ApplicationContext.run()) {
            BeanManager manager = context.getBean(BeanManager.class);
            java.lang.reflect.Type matching = io.micronaut.cdi.runtime.CdiParameterizedType.of(
                Result.class, new java.lang.reflect.Type[]{vars[0], vars[1]});
            assertEquals(1, manager.getBeans(matching).size(), "T1 extends RuntimeException, T2 extends T1");
            java.lang.reflect.Type notMatching = io.micronaut.cdi.runtime.CdiParameterizedType.of(
                Result.class, new java.lang.reflect.Type[]{vars[0], vars[2]});
            assertEquals(0, manager.getBeans(notMatching).size(), "T3 is unbounded, beyond Exception");
        }
    }
}
