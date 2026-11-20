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
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A producer field of a class whose {@code Typed} names a type the product has as well compiles, and its product is
 * resolved by its own types (CDI 4.1 section 2.2.2): the types written for the product and those of the class are
 * not named twice.
 */
class TypedProducerFieldTypesTest {

    @Test
    void aProducerFieldOfTheTypeTheClassIsTypedToProduces() {
        try (ApplicationContext context = InMemoryDeployment.start("typedfield.Holder", """
            package typedfield;
            public class Holder {
                public interface Svc {
                    String id();
                }
                public interface S3 extends Svc {
                }
                public record S3Impl(String id) implements S3 {
                }
                @jakarta.enterprise.context.Dependent
                @jakarta.enterprise.inject.Typed(S3.class)
                public static class B3 implements S3 {
                    @jakarta.enterprise.inject.Produces
                    @jakarta.inject.Named("p2")
                    S3 p2 = new S3Impl("P2");
                    public String id() {
                        return "B3";
                    }
                }
            }
            """)) {
            assertProduced(context, "typedfield.Holder$S3");
        }
    }

    @Test
    void aProducerFieldOfASubtypeOfTheTypeTheClassIsTypedToProduces() {
        try (ApplicationContext context = InMemoryDeployment.start("typedsuper.Holder", """
            package typedsuper;
            public class Holder {
                public interface Svc {
                    String id();
                }
                public interface S3 extends Svc {
                }
                public record S3Impl(String id) implements S3 {
                }
                @jakarta.enterprise.context.Dependent
                @jakarta.enterprise.inject.Typed(Svc.class)
                public static class B3 implements S3 {
                    @jakarta.enterprise.inject.Produces
                    @jakarta.inject.Named("p2")
                    S3 p2 = new S3Impl("P2");
                    public String id() {
                        return "B3";
                    }
                }
            }
            """)) {
            assertProduced(context, "typedsuper.Holder$S3");
        }
    }

    @Test
    void aSubclassTypedToObjectInheritingAProducerFieldOfNoTypeCompiles() {
        try (ApplicationContext context = InMemoryDeployment.start("typedobject.Holder", """
            package typedobject;
            public class Holder {
                public interface Svc {
                    String id();
                }
                public interface S3 extends Svc {
                }
                public record S3Impl(String id) implements S3 {
                }
                public interface Gen<T> {
                    T value();
                }
                @jakarta.enterprise.context.Dependent
                public static class Base implements Svc {
                    @jakarta.enterprise.inject.Produces
                    @jakarta.enterprise.context.ApplicationScoped
                    @jakarta.enterprise.inject.Typed({})
                    Gen<String> text = () -> "text";
                    @jakarta.enterprise.inject.Produces
                    @jakarta.inject.Named("p2")
                    S3 p2 = new S3Impl("P2");
                    public String id() {
                        return "base";
                    }
                }
                @jakarta.enterprise.context.Dependent
                public static class Middle extends Base {
                }
                @jakarta.enterprise.context.ApplicationScoped
                @jakarta.enterprise.inject.Typed({Leaf.class, Base.class, Svc.class, Object.class})
                public static class Leaf extends Middle {
                    @jakarta.enterprise.inject.Produces
                    Gen<Integer> number() {
                        return () -> 7;
                    }
                    @Override
                    public String id() {
                        return "leaf";
                    }
                }
            }
            """)) {
            assertProduced(context, "typedobject.Holder$S3");
            try {
                Class<?> leaf = context.getClassLoader().loadClass("typedobject.Holder$Leaf");
                Object found = context.getBean(CdiBeanContainer.class).createInstance().select(leaf).get();
                assertEquals("leaf", leaf.getMethod("id").invoke(found));
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private static void assertProduced(ApplicationContext context, String type) {
        try {
            Class<?> produced = context.getClassLoader().loadClass(type);
            // the product by its name: the class typed to the produced type is a bean of it as well
            Instance<?> instance = context.getBean(CdiBeanContainer.class).createInstance()
                .select(produced, jakarta.enterprise.inject.literal.NamedLiteral.of("p2"));
            assertEquals(false, instance.isAmbiguous());
            assertEquals("P2", produced.getMethod("id").invoke(instance.get()));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
