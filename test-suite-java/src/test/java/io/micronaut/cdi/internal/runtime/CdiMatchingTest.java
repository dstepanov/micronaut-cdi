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
package io.micronaut.cdi.internal.runtime;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.Qualifier;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Indexed;
import io.micronaut.core.type.Argument;
import io.micronaut.inject.BeanDefinition;
import io.micronaut.inject.qualifiers.Qualifiers;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Alternative;
import jakarta.enterprise.util.Nonbinding;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The qualifier the container asks the bean context for the beans of a lookup with selects what the rules of
 * section 2.4.2 select among the definitions the bean context enumerates for the raw class of the type, and
 * compares and composes like any other Micronaut qualifier.
 */
class CdiMatchingTest {

    @jakarta.inject.Qualifier
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Tinted {
        String value() default "";

        @Nonbinding
        String note() default "";
    }

    @jakarta.inject.Qualifier
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Matte {
    }

    public interface Swatch {
    }

    @Dependent
    public static class PlainSwatch implements Swatch {
    }

    @Dependent
    @Tinted(value = "red", note = "warm")
    public static class RedSwatch implements Swatch {
    }

    @Dependent
    @Tinted("blue")
    @Matte
    public static class BlueMatteSwatch implements Swatch {
    }

    public interface Tray<T> {
    }

    @Dependent
    public static class SwatchTray implements Tray<Swatch> {
    }

    @Dependent
    public static class StringTray implements Tray<String> {
    }

    public interface Lamp {
    }

    @Dependent
    public static class OldLamp implements Lamp {
    }

    @Dependent
    @Replaces(OldLamp.class)
    public static class NewLamp implements Lamp {
    }

    public interface Catalogue {
    }

    @Dependent
    @Indexed(Catalogue.class)
    public static class CataloguedOnly {
    }

    public interface Finish {
    }

    @Dependent
    public static class GlossFinish implements Finish {
    }

    @Dependent
    @Alternative
    public static class SatinFinish implements Finish {
    }

    @Test
    void selectsWhatTheContainerSelects() {
        try (ApplicationContext context = ApplicationContext.run()) {
            CdiBeanContainer container = context.getBean(CdiBeanContainer.class);
            CompiledAnnotationTypes types = CompiledAnnotationTypes.of(context);
            CdiQualifier red = CdiQualifier.ofValue(types, AnnotationValue.builder(Tinted.class).value("red").build());
            CdiQualifier blue = CdiQualifier.ofValue(types, AnnotationValue.builder(Tinted.class).value("blue").build());
            CdiQualifier matte = CdiQualifier.ofValue(types, AnnotationValue.builder(Matte.class).build());

            assertSelects(context, container, Argument.of(Swatch.class), List.of(), PlainSwatch.class);
            assertSelects(context, container, Argument.of(Swatch.class), List.of(CdiQualifier.ANY),
                PlainSwatch.class, RedSwatch.class, BlueMatteSwatch.class);
            // the non-binding member takes no part
            assertSelects(context, container, Argument.of(Swatch.class), List.of(red), RedSwatch.class);
            assertSelects(context, container, Argument.of(Swatch.class), List.of(matte, blue), BlueMatteSwatch.class);
            assertSelects(context, container, Argument.of(Swatch.class), List.of(red, matte));
            // a raw lookup of a parameterized bean type, and a parameterized lookup the raw class narrows
            assertSelects(context, container, Argument.of(Tray.class), List.of());
            assertSelects(context, container, Argument.of(Tray.class, Swatch.class), List.of(), SwatchTray.class);
            assertSelects(context, container, Argument.of(Tray.class, String.class), List.of(), StringTray.class);
            // the replaced bean is not among the candidates the qualifier is handed
            assertSelects(context, container, Argument.of(Lamp.class), List.of(), NewLamp.class);
            // a definition the context only indexes under the type is no bean of it
            assertSelects(context, container, Argument.of(Catalogue.class), List.of(CdiQualifier.ANY));
            // an alternative no priority selected is not there to select
            assertSelects(context, container, Argument.of(Finish.class), List.of(), GlossFinish.class);
        }
    }

    @Test
    void comparesByTheTypeAndTheQualifiersInAnyOrder() {
        try (ApplicationContext context = ApplicationContext.run()) {
            CdiBeanContainer container = context.getBean(CdiBeanContainer.class);
            CompiledAnnotationTypes types = CompiledAnnotationTypes.of(context);
            CdiQualifier blue = CdiQualifier.ofValue(types, AnnotationValue.builder(Tinted.class).value("blue").build());
            CdiQualifier matte = CdiQualifier.ofValue(types, AnnotationValue.builder(Matte.class).build());
            CdiQualifier blueAgain = CdiQualifier.ofValue(types,
                AnnotationValue.builder(Tinted.class).value("blue").build());

            CdiMatching<Object> one = CdiMatching.of(container, Argument.of(Swatch.class), List.of(blue, matte));
            CdiMatching<Object> other = CdiMatching.of(container, Argument.of(Swatch.class), List.of(matte, blueAgain));
            assertEquals(one, other);
            assertEquals(one.hashCode(), other.hashCode());
            assertNotEquals(one, CdiMatching.of(container, Argument.of(Finish.class), List.of(blue, matte)));
            assertNotEquals(one, CdiMatching.of(container, Argument.of(Swatch.class), List.of(blue)));
            assertNotEquals(CdiMatching.of(container, Argument.of(Tray.class, String.class), List.of()),
                CdiMatching.of(container, Argument.of(Tray.class, Swatch.class), List.of()));

            CdiMatching<Object> fewer = CdiMatching.of(container, Argument.of(Swatch.class), List.of(blue));
            assertTrue(one.contains(fewer), "requiring more selects among what requiring less selects");
            assertFalse(fewer.contains(one));
            assertFalse(one.contains(CdiMatching.of(container, Argument.of(Finish.class), List.of(blue))));
        }
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void composesWithTheQualifiersOfAnApplication() {
        try (ApplicationContext context = ApplicationContext.run()) {
            CdiBeanContainer container = context.getBean(CdiBeanContainer.class);
            CdiMatching<Object> any = CdiMatching.of(container, Argument.of(Swatch.class), List.of(CdiQualifier.ANY));
            Qualifier<Object> byType = Qualifiers.byType(RedSwatch.class);
            Qualifier<Object> both = Qualifiers.byQualifiers(any, byType);
            Collection<BeanDefinition<Object>> found = context.getBeanDefinitions(any.lookupType(), both);
            assertEquals(Set.of(RedSwatch.class), beanTypesOf((Collection) found));
            assertTrue(both.contains(any));
            assertTrue(both.contains(byType));
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void assertSelects(ApplicationContext context, CdiBeanContainer container, Argument<?> type,
                                      List<CdiQualifier> qualifiers, Class<?>... expected) {
        CdiMatching<Object> matching = CdiMatching.of(container, type, qualifiers);
        Collection<BeanDefinition<Object>> found = context.getBeanDefinitions(matching.lookupType(), matching);
        Set<Class<?>> wanted = Set.of(expected);
        assertEquals(wanted, beanTypesOf((Collection) found), "the qualifier for " + matching);
        assertEquals(wanted, beanTypesOf((Collection) container.definitionsOf((Argument) type, qualifiers, null)),
            "the container for " + matching);
    }

    private static Set<Class<?>> beanTypesOf(Collection<BeanDefinition<?>> definitions) {
        Set<Class<?>> types = new LinkedHashSet<>();
        for (BeanDefinition<?> definition : definitions) {
            types.add(definition.getBeanType());
        }
        return types;
    }
}
