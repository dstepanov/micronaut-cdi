/*
 * Copyright 2017-2026 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.cdi.test;

import io.micronaut.cdi.test.inheritance.BoundInitializer;
import io.micronaut.cdi.test.inheritance.GenericInitializer;
import io.micronaut.context.ApplicationContext;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.spi.AnnotatedParameter;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.enterprise.inject.spi.InjectionPoint;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * The member of an injection point is the one the point was compiled for, among overloads of one name and among
 * parameters of one type (section 5.5.7): it is identified by its parameter types and its position, not guessed
 * from the type it injects.
 */
class OverloadedInjectionPointTest {

    @Dependent
    public static class Foo {
    }

    @Dependent
    public static class Bar {
    }

    /**
     * Told the injection point it is injected into.
     */
    @Dependent
    public static class Probe {

        @Inject
        InjectionPoint at;
    }

    /**
     * Declares two initializers of one name that both take a {@code Foo}, and one that takes two.
     */
    @Dependent
    public static class Overloads {

        Probe alone;
        Probe beside;

        @Inject
        void set(Foo foo) {
        }

        @Inject
        void set(Bar bar, Foo foo) {
        }

        @Inject
        void pair(Foo first, Foo second) {
        }

        @Inject
        void probe(Probe probe) {
            this.alone = probe;
        }

        @Inject
        void probe(Bar bar, Probe probe) {
            this.beside = probe;
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> Bean<T> beanOf(BeanManager manager, Class<T> type) {
        return (Bean<T>) manager.resolve(manager.getBeans(type));
    }

    @Test
    void eachPointOfAnOverloadNamesItsOwnOverload() {
        try (ApplicationContext context = ApplicationContext.run()) {
            Bean<Overloads> bean = beanOf(context.getBean(BeanManager.class), Overloads.class);

            List<InjectionPoint> fooPointsOfSet = bean.getInjectionPoints().stream()
                .filter(point -> point.getMember().getName().equals("set") && point.getType() == Foo.class)
                .toList();

            assertEquals(2, fooPointsOfSet.size());
            Set<Integer> parameterCounts = new java.util.HashSet<>();
            for (InjectionPoint point : fooPointsOfSet) {
                Method member = assertInstanceOf(Method.class, point.getMember());
                parameterCounts.add(member.getParameterCount());
                AnnotatedParameter<?> parameter = assertInstanceOf(AnnotatedParameter.class, point.getAnnotated());
                assertEquals(Foo.class, member.getParameterTypes()[parameter.getPosition()]);
            }
            assertEquals(Set.of(1, 2), parameterCounts, "each point names the overload that declares it");
        }
    }

    @Test
    void twoParametersOfOneTypeAreTwoPositions() {
        try (ApplicationContext context = ApplicationContext.run()) {
            Bean<Overloads> bean = beanOf(context.getBean(BeanManager.class), Overloads.class);

            List<InjectionPoint> pairPoints = bean.getInjectionPoints().stream()
                .filter(point -> point.getMember().getName().equals("pair"))
                .toList();

            assertEquals(2, pairPoints.size());
            Set<Integer> positions = new java.util.HashSet<>();
            for (InjectionPoint point : pairPoints) {
                positions.add(assertInstanceOf(AnnotatedParameter.class, point.getAnnotated()).getPosition());
            }
            assertEquals(Set.of(0, 1), positions);
        }
    }

    @Test
    void anInheritedMemberIsFoundByTheTypesTheSubclassBindsItsVariablesTo() {
        try (ApplicationContext context = ApplicationContext.run()) {
            Bean<BoundInitializer> bean = beanOf(context.getBean(BeanManager.class), BoundInitializer.class);

            InjectionPoint point = bean.getInjectionPoints().iterator().next();

            assertEquals(String[].class, point.getType());
            Method member = assertInstanceOf(Method.class, point.getMember());
            assertEquals("setAll", member.getName());
            assertEquals(GenericInitializer.class, member.getDeclaringClass());
            assertEquals(0, assertInstanceOf(AnnotatedParameter.class, point.getAnnotated()).getPosition());
        }
    }

    @Test
    void aBeanInjectedIntoAnOverloadIsToldThatOverload() {
        try (ApplicationContext context = ApplicationContext.run()) {
            Overloads overloads = context.getBean(BeanManager.class).createInstance().select(Overloads.class).get();

            Method alone = assertInstanceOf(Method.class, overloads.alone.at.getMember());
            Method beside = assertInstanceOf(Method.class, overloads.beside.at.getMember());

            assertNotEquals(alone, beside);
            assertEquals(List.of(Probe.class), List.of(alone.getParameterTypes()));
            assertEquals(List.of(Bar.class, Probe.class), List.of(beside.getParameterTypes()));
            assertEquals(1, assertInstanceOf(AnnotatedParameter.class, overloads.beside.at.getAnnotated()).getPosition());
        }
    }
}
