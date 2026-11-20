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

import io.micronaut.cdi.internal.runtime.CdiBeanContainer;
import io.micronaut.context.ApplicationContext;
import jakarta.enterprise.util.TypeLiteral;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A subclass of no type, two levels below a class with a producer field of no type, declaring a qualified producer
 * method of its own, compiles and produces: the bean types each producer and each class narrow themselves to stay
 * their own.
 */
class TypedAwayProducerHierarchyTest {

    @Test
    void producersOfNoTypeAlongAHierarchyCompileAndProduce() throws Exception {
        try (ApplicationContext context = InMemoryDeployment.start("typedhierarchy.Base", """
            package typedhierarchy;

            @jakarta.enterprise.context.Dependent
            public class Base {

                public interface Gen<T> {
                    T value();
                }

                @jakarta.inject.Qualifier
                @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
                public @interface Marked {
                    String value();
                }

                @jakarta.enterprise.inject.Produces
                @jakarta.enterprise.inject.Typed
                Gen<String> text = () -> "text";

                @jakarta.enterprise.context.Dependent
                public static class Middle extends Base {
                }

                @jakarta.enterprise.context.Dependent
                @jakarta.enterprise.inject.Typed
                public static class Leaf extends Middle {
                    @jakarta.enterprise.inject.Produces
                    @Marked("b")
                    Gen<Integer> number() {
                        return () -> 7;
                    }
                }
            }
            """)) {
            // the Gen<Integer> of the leaf keeps its own type, and the Gen<String> field of no type is no Gen
            List<String> gens = context.getBean(CdiBeanContainer.class)
                .getBeans(Object.class, new jakarta.enterprise.inject.Any.Literal()).stream()
                .flatMap(bean -> bean.getTypes().stream()).map(Object::toString)
                .filter(type -> type.contains("typedhierarchy.Base$Gen")).toList();
            assertEquals(List.of("typedhierarchy.Base$Gen<java.lang.Integer>"), gens);
        }
    }

    @Test
    void aProducerFieldIsNotInheritedByASubclassThatProducesToo() throws Exception {
        try (ApplicationContext context = InMemoryDeployment.start("inheritedfield.Base", """
            package inheritedfield;

            @jakarta.enterprise.context.Dependent
            public class Base {

                public static class Label {
                }

                public static class Tag {
                }

                @jakarta.enterprise.inject.Produces
                Label label = new Label();

                @jakarta.enterprise.inject.Produces
                Badge badge() {
                    return new Badge();
                }

                public static class Badge {
                }

                @jakarta.enterprise.context.Dependent
                public static class Sub extends Base {
                    @jakarta.enterprise.inject.Produces
                    Tag tag() {
                        return new Tag();
                    }
                }
            }
            """)) {
            Class<?> label = context.getClassLoader().loadClass("inheritedfield.Base$Label");
            // the field of the base alone produces a label: the subclass, a producer itself, inherits no producer -
            // to the container, to a lookup and to Micronaut alike
            CdiBeanContainer container = context.getBean(CdiBeanContainer.class);
            assertEquals(1, container.getBeans(label).size());
            jakarta.enterprise.inject.Instance<?> labels = container.createInstance().select(label);
            assertEquals(1, labels.stream().count());
            org.junit.jupiter.api.Assertions.assertFalse(labels.isAmbiguous());
            assertEquals(1, context.getBeansOfType(label).size());
            // and a producer method is not inherited either
            Class<?> badge = context.getClassLoader().loadClass("inheritedfield.Base$Badge");
            assertEquals(1, container.getBeans(badge).size());
        }
    }
}
