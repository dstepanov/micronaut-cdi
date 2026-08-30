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
package io.micronaut.cdi.tck.arquillian;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.Qualifier;
import io.micronaut.core.type.Argument;
import io.micronaut.inject.qualifiers.Qualifiers;
import org.jboss.arquillian.test.spi.TestEnricher;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Injects the kit's test instances from the deployment under test.
 *
 * <p>A test class of the kit declares injection points the way a bean does — {@code @Inject BeanManager
 * beanManager} on every one of them, and the beans of its own scenario on many — and expects to be injected
 * before its methods run. The test class is not a bean of the deployment, so the container did not compile an
 * injection for it; the enricher resolves each field from the deployment's container instead, which is the same
 * resolution an injection point gets, performed by the harness.</p>
 */
public final class MicronautTestEnricher implements TestEnricher {

    @Override
    public void enrich(Object testCase) {
        ApplicationContext context;
        try {
            context = CurrentDeployment.context();
        } catch (IllegalStateException e) {
            // no deployment is under test, and there is nothing to inject from
            return;
        }
        for (Class<?> type = testCase.getClass(); type != null && type != Object.class;
             type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (!field.isAnnotationPresent(jakarta.inject.Inject.class)) {
                    continue;
                }
                Object value = resolve(context, field);
                if (value == null) {
                }
                if (value != null) {
                    field.setAccessible(true);
                    try {
                        field.set(testCase, value);
                    } catch (IllegalAccessException e) {
                        throw new IllegalStateException("The field " + field + " of the test could not be set", e);
                    }
                }
            }
        }
    }

    private static Object resolve(ApplicationContext context, Field field) {
        Object special = resolveBuiltIn(context, field.getType(), field.getGenericType(),
            field.getAnnotations());
        if (special != null) {
            return special;
        }
        Argument<?> argument = Argument.of(field.getGenericType());
        Qualifier<Object> qualifier = qualifierOf(field);
        try {
            return context.getBean((Argument<Object>) argument, qualifier);
        } catch (RuntimeException e) {
            // what cannot be resolved is left null, and the test that needed it will say so
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Qualifier<Object> qualifierOf(Field field) {
        List<Qualifier<Object>> qualifiers = new ArrayList<>();
        for (Annotation annotation : field.getAnnotations()) {
            if (annotation.annotationType().isAnnotationPresent(jakarta.inject.Qualifier.class)
                && !(annotation instanceof jakarta.enterprise.inject.Any)) {
                qualifiers.add(Qualifiers.byAnnotation(annotation));
            }
        }
        if (qualifiers.isEmpty()) {
            return null;
        }
        if (qualifiers.size() == 1) {
            return qualifiers.get(0);
        }
        return Qualifiers.byQualifiers(qualifiers.toArray(new Qualifier[0]));
    }

    @Override
    public Object[] resolve(Method method) {
        Object[] values = new Object[method.getParameterCount()];
        ApplicationContext context;
        try {
            context = CurrentDeployment.context();
        } catch (IllegalStateException e) {
            return values;
        }
        java.lang.reflect.Parameter[] parameters = method.getParameters();
        for (int i = 0; i < parameters.length; i++) {
            Object special = resolveBuiltIn(context, parameters[i].getType(),
                parameters[i].getParameterizedType(), parameters[i].getAnnotations());
            if (special != null) {
                values[i] = special;
                continue;
            }
            Argument<?> argument = Argument.of(parameters[i].getParameterizedType());
            Qualifier<Object> qualifier = qualifierOfAnnotations(parameters[i].getAnnotations());
            try {
                values[i] = context.getBean((Argument<Object>) argument, qualifier);
            } catch (RuntimeException e) {
                // what cannot be resolved is left null, and the test that needed it will say so
            }
        }
        return values;
    }

    /**
     * The built-in event of the kit's own injection points, built from the reflective type directly: the
     * compiled-argument road erases a wildcard, and a test's field can carry one.
     */
    private static @org.jspecify.annotations.Nullable Object resolveBuiltIn(
        ApplicationContext context, Class<?> rawType, java.lang.reflect.Type genericType,
        Annotation[] annotations) {
        if (rawType != jakarta.enterprise.event.Event.class
            || !(genericType instanceof java.lang.reflect.ParameterizedType parameterized)) {
            return null;
        }
        java.util.Set<Annotation> qualifiers = new java.util.LinkedHashSet<>();
        for (Annotation annotation : annotations) {
            if (annotation.annotationType().isAnnotationPresent(jakarta.inject.Qualifier.class)
                && !(annotation instanceof jakarta.enterprise.inject.Any)) {
                qualifiers.add(annotation);
            }
        }
        return new io.micronaut.cdi.runtime.CdiEvent<>(
            context.getBean(io.micronaut.cdi.runtime.ObserverRegistry.class),
            parameterized.getActualTypeArguments()[0], qualifiers, null);
    }

    @SuppressWarnings("unchecked")
    private static Qualifier<Object> qualifierOfAnnotations(Annotation[] annotations) {
        List<Qualifier<Object>> qualifiers = new ArrayList<>();
        for (Annotation annotation : annotations) {
            if (annotation.annotationType().isAnnotationPresent(jakarta.inject.Qualifier.class)
                && !(annotation instanceof jakarta.enterprise.inject.Any)) {
                qualifiers.add(Qualifiers.byAnnotation(annotation));
            }
        }
        if (qualifiers.isEmpty()) {
            return null;
        }
        if (qualifiers.size() == 1) {
            return qualifiers.get(0);
        }
        return Qualifiers.byQualifiers(qualifiers.toArray(new Qualifier[0]));
    }
}
