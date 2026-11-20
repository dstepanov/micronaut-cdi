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
import jakarta.enterprise.inject.literal.NamedLiteral;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The bean types of a producer are the closure of its return or field type, restricted by its own {@code Typed}, and
 * do not depend on the {@code Typed} of the class declaring it (CDI 4.1 sections 2.2.1 and 2.2.2); a subclass inherits
 * no producer (section 4.2). Each row crosses a producer method or field, a dependent or application scoped product,
 * the {@code Typed} of the declaring class and that of the producer, and looks the product up by its type, by its
 * superinterface and by Object; the subclass of the declaring class in each row, typed as the class is, contributes
 * no product.
 */
class ProducerTypesMatrixTest {

    /**
     * The Typed of the declaring class.
     */
    enum ClassTyped {
        NONE("", ""),
        EMPTY("@jakarta.enterprise.inject.Typed({})", ""),
        INTERFACE("@jakarta.enterprise.inject.Typed(Marker.class)", "implements Marker"),
        SUPERINTERFACE("@jakarta.enterprise.inject.Typed(S1.class)", "implements S1"),
        PRODUCED_TYPE("@jakarta.enterprise.inject.Typed(S2.class)", "implements S2");

        final String annotation;
        final String implementsClause;

        ClassTyped(String annotation, String implementsClause) {
            this.annotation = annotation;
            this.implementsClause = implementsClause;
        }
    }

    static Stream<Arguments> rows() {
        List<Arguments> rows = new ArrayList<>();
        for (boolean field : new boolean[] {false, true}) {
            for (boolean applicationScoped : new boolean[] {false, true}) {
                for (ClassTyped classTyped : ClassTyped.values()) {
                    for (boolean producerTyped : new boolean[] {false, true}) {
                        rows.add(Arguments.of(field, applicationScoped, classTyped, producerTyped));
                    }
                }
            }
        }
        return rows.stream();
    }

    @ParameterizedTest(name = "field={0} applicationScoped={1} classTyped={2} producerTypedToS1={3}")
    @MethodSource("rows")
    void theProductHasTheTypesOfItsProducerAlone(boolean field, boolean applicationScoped, ClassTyped classTyped,
                                                  boolean producerTyped) throws Exception {
        String pkg = "matrix" + (field ? "f" : "m") + (applicationScoped ? "a" : "d")
            + classTyped.name().toLowerCase().replace("_", "") + (producerTyped ? "t" : "n");
        String scope = applicationScoped ? "@jakarta.enterprise.context.ApplicationScoped" : "";
        String typed = producerTyped ? "@jakarta.enterprise.inject.Typed(S1.class)" : "";
        String producer = field
            ? "public S2 produced = new S2Impl(\"p\");"
            : "public S2 produce() { return new S2Impl(\"p\"); }";
        String source = """
            package %1$s;
            public class Holder {
                public interface S1 {
                    String id();
                }
                public interface S2 extends S1 {
                }
                public interface Marker {
                }
                public static class S2Impl implements S2 {
                    private final String id;
                    public S2Impl() {
                        this("none");
                    }
                    public S2Impl(String id) {
                        this.id = id;
                    }
                    public String id() {
                        return id;
                    }
                }
                @jakarta.enterprise.context.Dependent
                %2$s
                public static class Declaring %3$s {
                    @jakarta.enterprise.inject.Produces
                    %4$s
                    %5$s
                    @jakarta.inject.Named("p")
                    %6$s
                    public String id() {
                        return "declaring";
                    }
                }
                @jakarta.enterprise.context.Dependent
                %2$s
                public static class Inheriting extends Declaring {
                }
            }
            """.formatted(pkg, classTyped.annotation, classTyped.implementsClause, scope, typed, producer);
        try (ApplicationContext context = InMemoryDeployment.start(pkg + ".Holder", source)) {
            ClassLoader loader = context.getClassLoader();
            Class<?> s1 = loader.loadClass(pkg + ".Holder$S1");
            Class<?> s2 = loader.loadClass(pkg + ".Holder$S2");
            Map<String, Integer> expected = new LinkedHashMap<>();
            expected.put("S2", producerTyped ? 0 : 1);
            expected.put("S1", 1);
            expected.put("Object", 1);
            Map<String, Integer> actual = new LinkedHashMap<>();
            CdiBeanContainer container = context.getBean(CdiBeanContainer.class);
            actual.put("S2", container.getBeans(s2, NamedLiteral.of("p")).size());
            actual.put("S1", container.getBeans(s1, NamedLiteral.of("p")).size());
            actual.put("Object", container.getBeans(Object.class, NamedLiteral.of("p")).size());
            assertEquals(expected, actual, "the beans named p of each type");
            // the product is the one a lookup by its superinterface resolves to
            Instance<?> bySuperinterface = container.createInstance().select(s1, NamedLiteral.of("p"));
            assertEquals("p", s1.getMethod("id").invoke(bySuperinterface.get()));
            if (!producerTyped) {
                Instance<?> byType = container.createInstance().select(s2, NamedLiteral.of("p"));
                assertEquals("p", s1.getMethod("id").invoke(byType.get()));
            }
        }
    }
}
