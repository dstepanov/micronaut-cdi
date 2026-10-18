package io.micronaut.cdi.test.bce;

import io.micronaut.annotation.processing.test.JavaParser;
import io.micronaut.cdi.lang.model.ast.ElementClassInfo;
import io.micronaut.cdi.lang.model.ast.ElementTypes;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.ClassConfig;
import jakarta.enterprise.inject.build.compatible.spi.Enhancement;
import jakarta.enterprise.inject.build.compatible.spi.Types;
import jakarta.enterprise.lang.model.declarations.ClassInfo;
import jakarta.enterprise.lang.model.types.Type;
import jakarta.enterprise.lang.model.types.WildcardType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests composed wildcards against the language model of Java source during enhancement. */
class WildcardTypesTest {
    @Test
    void wildcardsComposeWithBoundsAndParameterizedTypes() {
        Checking.checked = false;
        try (JavaParser parser = ExtensionCompilation.of(Checking.class)) {
            parser.generate("wildcards.Subject", """
                package wildcards;
                import java.util.List;
                @jakarta.enterprise.context.Dependent
                public class Subject<T extends Number> {
                    List<?> unbounded;
                    List<? extends Object> object;
                    List<? extends Number> upper;
                    List<? super Integer> lower;
                    List<? extends List<String>> nested;
                    List<? extends String[]> array;
                    List<? extends T> variable;
                    List<List<? super Integer>> composed;
                }
                """);
        }
        assertTrue(Checking.checked, "The extension must exercise the factories during compilation");
    }

    public static class Checking implements BuildCompatibleExtension {
        static volatile boolean checked;

        @Enhancement(types = Object.class, withSubtypes = true)
        public void check(ClassConfig config, Types types) {
            ClassInfo info = config.info();
            if (!info.name().equals("wildcards.Subject")) {
                return;
            }
            WildcardType unbounded = types.wildcardUnbounded();
            WildcardType upper = types.wildcardWithUpperBound(types.of(Number.class));
            WildcardType lower = types.wildcardWithLowerBound(types.of(Integer.class));
            assertEquals(types.of(Object.class), unbounded.upperBound());
            assertNull(unbounded.lowerBound());
            assertEquals(types.of(Number.class), upper.upperBound());
            assertNull(upper.lowerBound());
            assertNull(lower.upperBound());
            assertEquals(types.of(Integer.class), lower.lowerBound());
            assertEquals(unbounded, types.wildcardWithUpperBound(types.of(Object.class)));
            assertEquals(unbounded.hashCode(), types.wildcardWithUpperBound(types.of(Object.class)).hashCode());
            assertTrue(unbounded.annotations().isEmpty());
            assertThrows(NullPointerException.class, () -> types.wildcardWithUpperBound(null));
            assertThrows(NullPointerException.class, () -> types.wildcardWithLowerBound(null));

            sameAsField(info, "unbounded", types.parameterized(List.class, unbounded));
            sameAsField(info, "object", types.parameterized(List.class, unbounded));
            sameAsField(info, "upper", types.parameterized(List.class, upper));
            sameAsField(info, "lower", types.parameterized(List.class, lower));
            sameAsField(info, "nested", types.parameterized(List.class,
                types.wildcardWithUpperBound(types.parameterized(List.class, String.class))));
            sameAsField(info, "array", types.parameterized(List.class,
                types.wildcardWithUpperBound(types.ofArray(types.of(String.class), 1))));
            sameAsField(info, "variable", types.parameterized(List.class,
                types.wildcardWithUpperBound(info.typeParameters().get(0))));
            sameAsField(info, "composed", types.parameterized(List.class, types.parameterized(List.class, lower)));
            checked = true;
        }

        private static void sameAsField(ClassInfo info, String name, Type composed) {
            Type declared = info.fields().stream().filter(field -> field.name().equals(name))
                .findFirst().orElseThrow().type();
            assertEquals(declared, composed, name);
            assertEquals(composed, declared, name);
            assertEquals(declared.hashCode(), composed.hashCode(), name);
            // composed again in the compilation the class was read in
            assertEquals(declared, ElementTypes.of(ElementTypes.elementOf(composed), ((ElementClassInfo) info).context()),
                name + " round trip");
        }
    }
}
