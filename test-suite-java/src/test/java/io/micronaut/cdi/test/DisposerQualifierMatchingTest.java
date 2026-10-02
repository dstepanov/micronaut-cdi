package io.micronaut.cdi.test;

import io.micronaut.annotation.processing.test.JavaParser;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/** Compiles isolated deployments so invalid disposer matches cannot hide behind another producer. */
class DisposerQualifierMatchingTest {
    private static final String SOURCE = """
        package broken;
        import jakarta.enterprise.context.Dependent;
        import jakarta.enterprise.inject.*;
        import jakarta.enterprise.util.Nonbinding;
        import jakarta.inject.*;
        import java.lang.annotation.*;

        @Qualifier @Retention(RetentionPolicy.RUNTIME) @Target({ElementType.METHOD, ElementType.FIELD, ElementType.PARAMETER, ElementType.TYPE})
        @Repeatable(Keys.class)
        @interface Key {
            String value() default "red";
            @Nonbinding String note() default "";
            int[] codes() default {1, 2};
        }
        @Retention(RetentionPolicy.RUNTIME) @Target({ElementType.METHOD, ElementType.FIELD, ElementType.PARAMETER, ElementType.TYPE})
        @interface Keys { Key[] value(); }
        @Qualifier @Retention(RetentionPolicy.RUNTIME) @Target({ElementType.METHOD, ElementType.FIELD, ElementType.PARAMETER})
        @interface Extra {}

        @Dependent %s
        public class Subject {
            @Produces %s String item%s
            void dispose(@Disposes %s String item) {}
        }
        """;

    @TestFactory
    List<DynamicTest> matchingQualifiers() {
        return List.of(
            accepts("nonbinding members", "@Key(note=\"producer\")", "@Key(note=\"disposer\")"),
            accepts("explicit defaults and array members", "@Key", "@Key(value=\"red\", codes={1,2}, note=\"ignored\")"),
            accepts("extra producer qualifier", "@Key @Extra", "@Key"),
            accepts("repeated qualifiers in reverse order", "@Key(\"red\") @Key(\"blue\")", "@Key(\"blue\") @Key(\"red\")"),
            accepts("subset of repeated qualifiers", "@Key(\"red\") @Key(\"blue\")", "@Key(\"blue\")"),
            accepts("explicit repeatable container", "@Keys({@Key(\"red\"), @Key(\"blue\")})", "@Key(\"blue\")"),
            accepts("any with another qualifier", "@Key @Extra", "@Any @Key"),
            accepts("implicit default", "", "@Default"),
            accepts("named bean has default", "@Named(\"item\")", ""),
            accepts("any-only bean has default", "@Any", ""),
            DynamicTest.dynamicTest("producer field", () -> compile("", "@Key(note=\"a\")", " = \"value\";", "@Key(note=\"b\")")),
            DynamicTest.dynamicTest("class qualifier does not qualify producer", () -> compile("@Key(\"class\")", "", "() { return \"value\"; }", ""))
        );
    }

    @TestFactory
    List<DynamicTest> mismatchingQualifiers() {
        return List.of(
            rejects("binding member differs", "@Key(\"red\")", "@Key(\"blue\")"),
            rejects("binding array differs", "@Key(codes={1,2})", "@Key(codes={2,1})"),
            rejects("any must not erase required qualifiers", "@Key(\"red\")", "@Any @Key(\"blue\")"),
            rejects("missing repeated qualifier", "@Key(\"red\") @Key(\"blue\")", "@Key(\"red\") @Key(\"green\")"),
            rejects("qualified producer has no implicit default", "@Key", ""),
            rejects("explicit default is required", "@Key", "@Default"),
            rejects("named qualifier differs", "@Named(\"first\")", "@Named(\"second\")")
        );
    }

    private static DynamicTest accepts(String name, String producer, String disposer) {
        return DynamicTest.dynamicTest(name, () -> assertDoesNotThrow(() ->
            compile("", producer, "() { return \"value\"; }", disposer)));
    }

    private static DynamicTest rejects(String name, String producer, String disposer) {
        return DynamicTest.dynamicTest(name, () -> InjectionDefinitionErrorTest.assertRefused(
            SOURCE.formatted("", producer, "() { return \"value\"; }", disposer), "No producer"));
    }

    private static void compile(String owner, String producer, String declaration, String disposer) {
        try (JavaParser parser = new JavaParser()) {
            parser.generate("broken.Subject", SOURCE.formatted(owner, producer, declaration, disposer));
        }
    }
}
