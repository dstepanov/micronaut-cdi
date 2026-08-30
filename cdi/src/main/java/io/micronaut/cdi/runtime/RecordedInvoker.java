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
package io.micronaut.cdi.runtime;

import io.micronaut.context.BeanContext;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.type.Argument;
import io.micronaut.inject.BeanDefinition;
import io.micronaut.inject.ExecutableMethod;
import io.micronaut.inject.ProxyBeanDefinition;
import jakarta.enterprise.inject.build.compatible.spi.InvokerInfo;
import jakarta.enterprise.invoke.Invoker;
import org.jspecify.annotations.Nullable;

import java.util.Map;

/**
 * One invoker an extension built (CDI 4.1, chapter 7): what the {@code InvokerBuilder} of the registration
 * phase records, and what invokes the method at runtime.
 *
 * <p>The registration phase runs while the bean is compiled, and building the invoker marks the method
 * executable, so that the compiled bean definition carries it — the invocation itself reads the compiled
 * {@link ExecutableMethod} rather than reflecting. What is recorded here is only what names it: the bean
 * class, the method, its parameter types, and what the builder asked to be looked up.</p>
 *
 * <p>The container the invocation goes to is the one running when it is made, which is what an invoker handed
 * from build time to runtime can mean by "the container".</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class RecordedInvoker implements InvokerInfo, Invoker<Object, Object> {

    private static final Map<String, Class<?>> PRIMITIVES = Map.of(
        "boolean", boolean.class, "byte", byte.class, "short", short.class, "char", char.class,
        "int", int.class, "long", long.class, "float", float.class, "double", double.class,
        "void", void.class);

    private final String beanClassName;
    private final String methodName;
    private final String[] parameterTypeNames;
    private final boolean staticMethod;
    private final boolean instanceLookup;
    private final boolean[] argumentLookups;

    public RecordedInvoker(String beanClassName, String methodName, String[] parameterTypeNames,
                           boolean staticMethod, boolean instanceLookup, boolean[] argumentLookups) {
        this.beanClassName = beanClassName;
        this.methodName = methodName;
        this.parameterTypeNames = parameterTypeNames;
        this.staticMethod = staticMethod;
        this.instanceLookup = instanceLookup;
        this.argumentLookups = argumentLookups;
    }

    @Override
    @SuppressWarnings("NullAway")
    public Object invoke(@Nullable Object instance, Object @Nullable [] arguments) throws Exception {
        CdiBeanContainer container = CdiRunning.current();
        if (container == null) {
            throw new IllegalStateException("No container is running to invoke "
                + beanClassName + "#" + methodName + " in");
        }
        BeanContext beanContext = container.beanContext();
        ClassLoader loader = beanContext.getClassLoader();
        Class<?> beanClass = Class.forName(beanClassName, false, loader);
        Class<?>[] parameterTypes = new Class<?>[parameterTypeNames.length];
        for (int i = 0; i < parameterTypeNames.length; i++) {
            parameterTypes[i] = classOf(parameterTypeNames[i], loader);
        }
        BeanDefinition<?> definition = definitionOf(beanContext, beanClass);
        ExecutableMethod<Object, Object> method = executable(definition, parameterTypes);

        if (arguments == null) {
            if (parameterTypes.length > 0) {
                throw new IllegalArgumentException("The method " + beanClassName + "#" + methodName + " takes "
                    + parameterTypes.length + " arguments, and was invoked with none");
            }
            arguments = new Object[0];
        }
        if (arguments.length < parameterTypes.length) {
            throw new IllegalArgumentException("The method " + beanClassName + "#" + methodName + " takes "
                + parameterTypes.length + " arguments, and was invoked with " + arguments.length);
        }

        CdiInstance<Object> lookup = new CdiInstance<>(beanContext, Argument.OBJECT_ARGUMENT);
        try {
            Object target;
            if (staticMethod) {
                target = null;
            } else if (instanceLookup) {
                // resolved the way the specification's lookup resolves — the @Default bean of the class, by
                // the rules of typesafe resolution — rather than by Micronaut's own null-qualifier rules
                @SuppressWarnings("unchecked")
                Object looked = lookup.selectArgument((Argument<Object>) Argument.of(beanClass)).get();
                target = looked;
            } else {
                if (instance == null) {
                    throw new NullPointerException("The method " + beanClassName + "#" + methodName
                        + " is not static, and was invoked without an instance");
                }
                if (!beanClass.isInstance(instance)) {
                    throw new ClassCastException("The instance " + instance.getClass().getName()
                        + " is not a " + beanClassName);
                }
                target = instance;
            }
            Object[] invocationArguments = new Object[parameterTypes.length];
            Argument<?>[] methodArguments = method.getArguments();
            for (int i = 0; i < parameterTypes.length; i++) {
                if (argumentLookups[i]) {
                    invocationArguments[i] = lookedUp(lookup, methodArguments[i]);
                } else {
                    invocationArguments[i] = checked(arguments[i], parameterTypes[i], i);
                }
            }
            return method.invoke(target, invocationArguments);
        } finally {
            // what an invocation looked up — the instance included, when it was looked up — lives only as
            // long as the invocation; a dependent among it is destroyed here
            lookup.destroyTransients();
        }
    }

    /**
     * Checks that every lookup the invoker names resolves to exactly one bean, which is what CDI 4.1 asks of
     * the deployment rather than of the first invocation.
     *
     * @param beanContext The started context
     */
    public void validateLookups(BeanContext beanContext) {
        boolean anyLookup = instanceLookup;
        for (boolean lookup : argumentLookups) {
            anyLookup = anyLookup || lookup;
        }
        if (!anyLookup) {
            return;
        }
        try {
            ClassLoader loader = beanContext.getClassLoader();
            Class<?> beanClass = Class.forName(beanClassName, false, loader);
            Class<?>[] parameterTypes = new Class<?>[parameterTypeNames.length];
            for (int i = 0; i < parameterTypeNames.length; i++) {
                parameterTypes[i] = classOf(parameterTypeNames[i], loader);
            }
            ExecutableMethod<Object, Object> method = executable(definitionOf(beanContext, beanClass), parameterTypes);
            CdiInstance<Object> lookup = new CdiInstance<>(beanContext, Argument.OBJECT_ARGUMENT);
            if (instanceLookup && !staticMethod) {
                resolvable(lookup, Argument.of(beanClass), "the instance of " + beanClassName);
            }
            Argument<?>[] methodArguments = method.getArguments();
            for (int i = 0; i < methodArguments.length; i++) {
                if (argumentLookups[i]) {
                    resolvable(lookup, methodArguments[i],
                        "the argument at " + i + " of " + beanClassName + "#" + methodName);
                }
            }
        } catch (ClassNotFoundException e) {
            throw new jakarta.enterprise.inject.spi.DeploymentException(
                "The invoker of " + beanClassName + "#" + methodName + " names a class that is not there", e);
        }
    }

    private static void resolvable(CdiInstance<Object> lookup, Argument<?> argument, String what) {
        java.util.Set<java.lang.annotation.Annotation> qualifiers =
            CdiQualifiers.declared(argument.getAnnotationMetadata());
        CdiInstance<?> selected = lookup.selectArgument(argument,
            qualifiers.toArray(new java.lang.annotation.Annotation[0]));
        if (selected.isUnsatisfied()) {
            throw new jakarta.enterprise.inject.spi.DeploymentException(
                "The lookup of " + what + " has no bean to satisfy it");
        }
        if (selected.isAmbiguous()) {
            throw new jakarta.enterprise.inject.spi.DeploymentException(
                "The lookup of " + what + " is ambiguous");
        }
    }

    /**
     * Looks the argument up the way an injection point of the parameter's type and qualifiers would resolve.
     */
    private static Object lookedUp(CdiInstance<Object> lookup, Argument<?> argument) {
        java.util.Set<java.lang.annotation.Annotation> qualifiers =
            CdiQualifiers.declared(argument.getAnnotationMetadata());
        return lookup
            .selectArgument(argument, qualifiers.toArray(new java.lang.annotation.Annotation[0]))
            .get();
    }

    /**
     * The given argument, checked against the parameter the way the specification asks: a reference of the
     * wrong class and a null where a primitive goes are the caller's errors, reported before the method runs.
     */
    private static @Nullable Object checked(@Nullable Object value, Class<?> parameterType, int position) {
        if (parameterType.isPrimitive()) {
            if (value == null) {
                throw new NullPointerException("The argument at " + position + " is null, and the parameter is "
                    + "the primitive " + parameterType.getName());
            }
            Object widened = Primitives.widen(value, parameterType);
            if (widened == null) {
                throw new ClassCastException("The argument at " + position + " is a "
                    + value.getClass().getName() + ", and the parameter is the primitive "
                    + parameterType.getName());
            }
            return widened;
        }
        if (value != null && !parameterType.isInstance(value)) {
            throw new ClassCastException("The argument at " + position + " is a " + value.getClass().getName()
                + ", and the parameter is a " + parameterType.getName());
        }
        return value;
    }

    private ExecutableMethod<Object, Object> executable(BeanDefinition<?> definition, Class<?>[] parameterTypes) {
        @SuppressWarnings("unchecked")
        ExecutableMethod<Object, Object> method = (ExecutableMethod<Object, Object>) definition
            .findMethod(methodName, parameterTypes)
            .orElseThrow(() -> new IllegalStateException("The method " + beanClassName + "#" + methodName
                + " was not compiled as an executable method"));
        return method;
    }

    private static BeanDefinition<?> definitionOf(BeanContext beanContext, Class<?> beanClass) {
        BeanDefinition<?> found = null;
        for (BeanDefinition<?> candidate : beanContext.getBeanDefinitions(beanClass)) {
            if (found == null || found instanceof ProxyBeanDefinition<?>) {
                // a plain definition is preferred, but a proxy's inherits the class's executable methods and
                // serves where it is the only one the context reports
                found = candidate;
            }
        }
        if (found == null) {
            throw new IllegalStateException("No bean of " + beanClass.getName() + " to invoke");
        }
        return found;
    }

    private static Class<?> classOf(String name, ClassLoader loader) throws ClassNotFoundException {
        int dimensions = 0;
        while (name.endsWith("[]")) {
            dimensions++;
            name = name.substring(0, name.length() - 2);
        }
        Class<?> primitive = PRIMITIVES.get(name);
        Class<?> component = primitive != null ? primitive
            : io.micronaut.core.reflect.ClassUtils.forName(name, loader).orElse(null);
        if (component == null) {
            throw new ClassNotFoundException(name);
        }
        for (int i = 0; i < dimensions; i++) {
            component = component.arrayType();
        }
        return component;
    }

    /**
     * The widening a primitive parameter accepts, which is the language's own: a boxed value of the parameter's
     * type, or of one the language widens to it.
     */
    private static final class Primitives {

        private Primitives() {
        }

        static @Nullable Object widen(Object value, Class<?> primitive) {
            if (primitive == boolean.class) {
                return value instanceof Boolean ? value : null;
            }
            if (primitive == char.class) {
                return value instanceof Character ? value : null;
            }
            if (primitive == byte.class) {
                return value instanceof Byte ? value : null;
            }
            if (primitive == short.class) {
                return value instanceof Short || value instanceof Byte ? ((Number) value).shortValue() : null;
            }
            if (primitive == int.class) {
                if (value instanceof Character character) {
                    return (int) character.charValue();
                }
                return value instanceof Integer || value instanceof Short || value instanceof Byte
                    ? ((Number) value).intValue() : null;
            }
            if (primitive == long.class) {
                if (value instanceof Character character) {
                    return (long) character.charValue();
                }
                return value instanceof Long || value instanceof Integer || value instanceof Short
                    || value instanceof Byte ? ((Number) value).longValue() : null;
            }
            if (primitive == float.class) {
                if (value instanceof Character character) {
                    return (float) character.charValue();
                }
                return value instanceof Float || value instanceof Long || value instanceof Integer
                    || value instanceof Short || value instanceof Byte ? ((Number) value).floatValue() : null;
            }
            if (primitive == double.class) {
                if (value instanceof Character character) {
                    return (double) character.charValue();
                }
                return value instanceof Double || value instanceof Float || value instanceof Long
                    || value instanceof Integer || value instanceof Short || value instanceof Byte
                    ? ((Number) value).doubleValue() : null;
            }
            return null;
        }
    }
}
