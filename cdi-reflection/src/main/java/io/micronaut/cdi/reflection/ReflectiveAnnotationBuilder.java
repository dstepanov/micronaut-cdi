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
package io.micronaut.cdi.reflection;

import io.micronaut.core.annotation.Internal;
import jakarta.enterprise.inject.build.compatible.spi.AnnotationBuilder;
import jakarta.enterprise.lang.model.AnnotationInfo;
import jakarta.enterprise.lang.model.AnnotationMember;
import jakarta.enterprise.lang.model.declarations.ClassInfo;
import jakarta.enterprise.lang.model.types.Type;

import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds an annotation the way section 2.10 lets an extension compose one: member by member, into a real
 * annotation instance that behaves as the annotation it names.
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
final class ReflectiveAnnotationBuilder implements AnnotationBuilder {

    private final Class<? extends Annotation> type;
    private final Map<String, Object> members = new LinkedHashMap<>();

    ReflectiveAnnotationBuilder(Class<? extends Annotation> type) {
        this.type = type;
    }

    @Override
    public AnnotationInfo build() {
        Annotation annotation = (Annotation) Proxy.newProxyInstance(type.getClassLoader(),
            new Class<?>[]{type}, new Built(type, Map.copyOf(members)));
        return ReflectionAnnotations.infoOf(annotation);
    }

    private AnnotationBuilder put(String name, Object value) {
        members.put(name, value);
        return this;
    }

    private static Class<?> classOf(ClassInfo info) {
        try {
            return Class.forName(info.name(), false, Thread.currentThread().getContextClassLoader() != null
                ? Thread.currentThread().getContextClassLoader()
                : ReflectiveAnnotationBuilder.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException("The class " + info.name() + " is not loadable here", e);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object enumOf(Class<?> enumType, String constant) {
        return Enum.valueOf((Class<? extends Enum>) enumType, constant);
    }

    @Override
    public AnnotationBuilder member(String name, boolean value) {
        return put(name, value);
    }

    @Override
    public AnnotationBuilder member(String name, boolean[] value) {
        return put(name, value);
    }

    @Override
    public AnnotationBuilder member(String name, byte value) {
        return put(name, value);
    }

    @Override
    public AnnotationBuilder member(String name, byte[] value) {
        return put(name, value);
    }

    @Override
    public AnnotationBuilder member(String name, short value) {
        return put(name, value);
    }

    @Override
    public AnnotationBuilder member(String name, short[] value) {
        return put(name, value);
    }

    @Override
    public AnnotationBuilder member(String name, int value) {
        return put(name, value);
    }

    @Override
    public AnnotationBuilder member(String name, int[] value) {
        return put(name, value);
    }

    @Override
    public AnnotationBuilder member(String name, long value) {
        return put(name, value);
    }

    @Override
    public AnnotationBuilder member(String name, long[] value) {
        return put(name, value);
    }

    @Override
    public AnnotationBuilder member(String name, float value) {
        return put(name, value);
    }

    @Override
    public AnnotationBuilder member(String name, float[] value) {
        return put(name, value);
    }

    @Override
    public AnnotationBuilder member(String name, double value) {
        return put(name, value);
    }

    @Override
    public AnnotationBuilder member(String name, double[] value) {
        return put(name, value);
    }

    @Override
    public AnnotationBuilder member(String name, char value) {
        return put(name, value);
    }

    @Override
    public AnnotationBuilder member(String name, char[] value) {
        return put(name, value);
    }

    @Override
    public AnnotationBuilder member(String name, String value) {
        return put(name, value);
    }

    @Override
    public AnnotationBuilder member(String name, String[] value) {
        return put(name, value);
    }

    @Override
    public AnnotationBuilder member(String name, Enum<?> value) {
        return put(name, value);
    }

    @Override
    public AnnotationBuilder member(String name, Enum<?>[] value) {
        return put(name, value);
    }

    @Override
    public AnnotationBuilder member(String name, Class<?> value) {
        return put(name, value);
    }

    @Override
    public AnnotationBuilder member(String name, Class<?>[] value) {
        return put(name, value);
    }

    @Override
    public AnnotationBuilder member(String name, Annotation value) {
        return put(name, value);
    }

    @Override
    public AnnotationBuilder member(String name, Annotation[] value) {
        return put(name, value);
    }

    @Override
    public AnnotationBuilder member(String name, AnnotationMember value) {
        throw new UnsupportedOperationException("An annotation member is composed from its value here");
    }

    @Override
    public AnnotationBuilder member(String name, Class<? extends Enum<?>> enumType, String enumValue) {
        return put(name, enumOf(enumType, enumValue));
    }

    @Override
    public AnnotationBuilder member(String name, Class<? extends Enum<?>> enumType, String[] enumValues) {
        Object array = java.lang.reflect.Array.newInstance(enumType, enumValues.length);
        for (int i = 0; i < enumValues.length; i++) {
            java.lang.reflect.Array.set(array, i, enumOf(enumType, enumValues[i]));
        }
        return put(name, array);
    }

    @Override
    public AnnotationBuilder member(String name, ClassInfo enumType, String enumValue) {
        return put(name, enumOf(classOf(enumType), enumValue));
    }

    @Override
    public AnnotationBuilder member(String name, ClassInfo enumType, String[] enumValues) {
        Class<?> loaded = classOf(enumType);
        Object array = java.lang.reflect.Array.newInstance(loaded, enumValues.length);
        for (int i = 0; i < enumValues.length; i++) {
            java.lang.reflect.Array.set(array, i, enumOf(loaded, enumValues[i]));
        }
        return put(name, array);
    }

    @Override
    public AnnotationBuilder member(String name, ClassInfo value) {
        return put(name, classOf(value));
    }

    @Override
    public AnnotationBuilder member(String name, ClassInfo[] values) {
        Class<?>[] classes = new Class<?>[values.length];
        for (int i = 0; i < values.length; i++) {
            classes[i] = classOf(values[i]);
        }
        return put(name, classes);
    }

    @Override
    public AnnotationBuilder member(String name, Type value) {
        if (value instanceof jakarta.enterprise.lang.model.types.ClassType classType) {
            return put(name, classOf(classType.declaration()));
        }
        throw new UnsupportedOperationException("A type member is composed from a class type here");
    }

    @Override
    public AnnotationBuilder member(String name, Type[] values) {
        Class<?>[] classes = new Class<?>[values.length];
        for (int i = 0; i < values.length; i++) {
            if (values[i] instanceof jakarta.enterprise.lang.model.types.ClassType classType) {
                classes[i] = classOf(classType.declaration());
            } else {
                throw new UnsupportedOperationException("A type member is composed from class types here");
            }
        }
        return put(name, classes);
    }

    @Override
    public AnnotationBuilder member(String name, AnnotationInfo value) {
        return put(name, ReflectionAnnotations.annotationOf(value));
    }

    @Override
    public AnnotationBuilder member(String name, AnnotationInfo[] values) {
        Annotation[] annotations = new Annotation[values.length];
        for (int i = 0; i < values.length; i++) {
            annotations[i] = ReflectionAnnotations.annotationOf(values[i]);
        }
        return put(name, annotations);
    }

    /**
     * The built annotation: it answers each member from what was composed, or from the member's default, and
     * compares the way the language says two annotations compare.
     *
     * @param type    The annotation type
     * @param members The composed members
     */
    private record Built(Class<? extends Annotation> type, Map<String, Object> members)
        implements InvocationHandler {

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            switch (method.getName()) {
                case "annotationType":
                    if (method.getParameterCount() == 0) {
                        return type;
                    }
                    break;
                case "equals":
                    if (method.getParameterCount() == 1) {
                        return annotationEquals(proxy, args[0]);
                    }
                    break;
                case "hashCode":
                    if (method.getParameterCount() == 0) {
                        return annotationHashCode();
                    }
                    break;
                case "toString":
                    if (method.getParameterCount() == 0) {
                        return "@" + type.getName() + members;
                    }
                    break;
                default:
                    break;
            }
            Object value = members.get(method.getName());
            if (value != null) {
                return value;
            }
            Object defaulted = method.getDefaultValue();
            if (defaulted != null) {
                return defaulted;
            }
            throw new IllegalStateException("The member " + method.getName() + " of " + type.getName()
                + " was not composed and has no default");
        }

        private boolean annotationEquals(Object proxy, Object other) throws Throwable {
            if (!(other instanceof Annotation annotation) || !type.equals(annotation.annotationType())) {
                return false;
            }
            for (Method member : type.getDeclaredMethods()) {
                Object mine = invoke(proxy, member, new Object[0]);
                Object theirs = member.invoke(annotation);
                if (mine.getClass().isArray() ? !Arrays.deepEquals(new Object[]{mine}, new Object[]{theirs})
                    : !mine.equals(theirs)) {
                    return false;
                }
            }
            return true;
        }

        private int annotationHashCode() throws Throwable {
            int hash = 0;
            for (Method member : type.getDeclaredMethods()) {
                Object value = invoke(this, member, new Object[0]);
                int valueHash = value.getClass().isArray()
                    ? Arrays.deepHashCode(new Object[]{value}) : value.hashCode();
                if (value.getClass().isArray()) {
                    // the language defines an annotation member array hash as Arrays.hashCode of the array
                    valueHash = arrayHash(value);
                }
                hash += (127 * member.getName().hashCode()) ^ valueHash;
            }
            return hash;
        }

        private static int arrayHash(Object array) {
            if (array instanceof Object[] objects) {
                return Arrays.hashCode(objects);
            }
            if (array instanceof int[] ints) {
                return Arrays.hashCode(ints);
            }
            if (array instanceof long[] longs) {
                return Arrays.hashCode(longs);
            }
            if (array instanceof byte[] bytes) {
                return Arrays.hashCode(bytes);
            }
            if (array instanceof short[] shorts) {
                return Arrays.hashCode(shorts);
            }
            if (array instanceof boolean[] booleans) {
                return Arrays.hashCode(booleans);
            }
            if (array instanceof char[] chars) {
                return Arrays.hashCode(chars);
            }
            if (array instanceof float[] floats) {
                return Arrays.hashCode(floats);
            }
            if (array instanceof double[] doubles) {
                return Arrays.hashCode(doubles);
            }
            return array.hashCode();
        }
    }
}
