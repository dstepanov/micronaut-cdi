package io.micronaut.cdi.test;

import io.micronaut.cdi.internal.runtime.CdiBeanContainer;
import io.micronaut.context.ApplicationContext;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.util.Nonbinding;
import jakarta.inject.Qualifier;
import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Matching chooses and invokes the right disposer for both producer methods and fields. */
class DisposerQualifierLifecycleTest {
    @Qualifier
    @Repeatable(Tags.class)
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.METHOD, ElementType.FIELD, ElementType.PARAMETER})
    @interface Tag {
        String value();
        @Nonbinding String note() default "";
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.METHOD, ElementType.FIELD, ElementType.PARAMETER})
    @interface Tags {
        Tag[] value();
    }

    record Product(String name) {
    }

    @Dependent
    static class Factory {
        static final List<String> DISPOSED = new ArrayList<>();

        @Produces @Tag("shared") @Tag(value = "method", note = "producer")
        Product method() {
            return new Product("method");
        }

        @Produces @Tag("shared") @Tag("field")
        Product field = new Product("field");

        void disposeMethod(@Disposes @Tag(value = "method", note = "disposer") Product product) {
            DISPOSED.add("method:" + product.name());
        }

        void disposeField(@Disposes @Any @Tag("field") Product product) {
            DISPOSED.add("field:" + product.name());
        }
    }

    @Test
    void destroysEachProductThroughItsMatchingDisposer() {
        Factory.DISPOSED.clear();
        try (ApplicationContext context = ApplicationContext.run()) {
            Instance<Product> products = context.getBean(CdiBeanContainer.class).createInstance()
                .select(Product.class, Any.Literal.INSTANCE);
            List<Product> created = products.stream().toList();
            assertEquals(2, created.size());
            created.forEach(products::destroy);
            assertEquals(List.of("field:field", "method:method"), Factory.DISPOSED.stream().sorted().toList());
        }
        assertEquals(2, Factory.DISPOSED.size(), "Products must not be disposed twice at shutdown");
    }
}
