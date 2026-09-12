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

import io.micronaut.cdi.runtime.CdiBeanContainer;
import io.micronaut.context.ApplicationContext;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.enterprise.inject.Stereotype;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.inject.Named;
import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A stereotype may be declared on a stereotype, and what it carries reaches the bean through the chain.
 *
 * <p>Section 2.7.2 has a stereotype declared on another stereotype contributing everything it declares - a scope,
 * a name, that the bean is an alternative, its priority - to whatever the second stereotype is declared on. The
 * chain may be more than one long, and it may come back on itself: a stereotype that declares a stereotype that
 * declares the first is not an error, it is the same set of stereotypes reached twice.</p>
 */
class StereotypeCompositionTest {

    @Test
    void everythingAStereotypeChainDeclaresReachesTheBean() {
        try (ApplicationContext context = ApplicationContext.run()) {
            CdiBeanContainer container = context.getBean(CdiBeanContainer.class);
            Bean<?> bean = container.getBeans(Beacon.class).iterator().next();
            // the scope comes from the far end of the chain
            assertEquals(RequestScoped.class, bean.getScope());
            // so does the name, which the chain asks for without saying what it is
            assertEquals("beacon", bean.getName());
            assertTrue(bean.isAlternative());
        }
    }

    @Test
    void aStereotypeThatComesBackOnItselfIsStillOneSetOfStereotypes() {
        try (ApplicationContext context = ApplicationContext.run()) {
            CdiBeanContainer container = context.getBean(CdiBeanContainer.class);
            Bean<?> bean = container.getBeans(Buoy.class).iterator().next();
            assertEquals(RequestScoped.class, bean.getScope());
            Set<Class<? extends java.lang.annotation.Annotation>> stereotypes = bean.getStereotypes();
            assertTrue(stereotypes.contains(Navigational.class), () -> "not among " + stereotypes);
            assertTrue(stereotypes.contains(Circular.class), () -> "not among " + stereotypes);
        }
    }

    @Test
    void aScopeTheBeanDeclaresItselfWinsOverTheOneItsStereotypeCarries() {
        try (ApplicationContext context = ApplicationContext.run()) {
            CdiBeanContainer container = context.getBean(CdiBeanContainer.class);
            Bean<?> bean = container.getBeans(Lamp.class).iterator().next();
            assertEquals(Dependent.class, bean.getScope());
        }
    }

    /**
     * The far end of the chain: the scope, the name and the alternative are declared here.
     */
    @Stereotype
    @RequestScoped
    @Named
    @Alternative
    @Priority(10)
    @Target(ElementType.TYPE)
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Lit {
    }

    /**
     * The near end, which declares nothing of its own beyond the stereotype it carries.
     */
    @Stereotype
    @Lit
    @Target(ElementType.TYPE)
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Navigational {
    }

    /**
     * Declares the stereotype that declares it, so that the chain comes back on itself.
     */
    @Stereotype
    @Navigational
    @Target(ElementType.TYPE)
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Circular {
    }

    /**
     * Reached through a chain two stereotypes long.
     */
    @Navigational
    public static class Beacon {
    }

    /**
     * Reached through a chain that comes back on itself.
     */
    @Circular
    public static class Buoy {
    }

    /**
     * Declares its own scope, which section 2.7.2 has winning over the stereotype's.
     */
    @Lit
    @Dependent
    public static class Lamp {
    }
}
