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
package io.micronaut.cdi.runtime.extension;

import io.micronaut.core.annotation.Internal;
import jakarta.enterprise.lang.model.AnnotationInfo;
import jakarta.enterprise.lang.model.AnnotationMember;

import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads an annotation of the language model back as a live annotation instance, member by member: what a
 * synthetic component's parameters carry when an extension composed an annotation for them.
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
final class LangModelAnnotations {

    private LangModelAnnotations() {
    }

    /**
     * The annotation instance the given description describes.
     *
     * @param info        The description
     * @param classLoader Where the annotation's classes load from
     * @return The annotation
     */
    @SuppressWarnings("unchecked")
    static Annotation annotationOf(AnnotationInfo info, ClassLoader classLoader) {
        Class<? extends Annotation> type;
        try {
            type = (Class<? extends Annotation>) Class.forName(info.name(), false, classLoader);
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException("The annotation " + info.name() + " is not loadable here", e);
        }
        Map<String, Object> members = new LinkedHashMap<>();
        for (Map.Entry<String, AnnotationMember> entry : info.members().entrySet()) {
            members.put(entry.getKey(), valueOf(entry.getValue(), type, entry.getKey(), classLoader));
        }
        return (Annotation) Proxy.newProxyInstance(classLoader, new Class<?>[]{type},
            new MemberAnswers(type, members));
    }

    private static Object valueOf(AnnotationMember member, Class<? extends Annotation> type, String name,
                                  ClassLoader classLoader) {
        return switch (member.kind()) {
            case BOOLEAN -> member.asBoolean();
            case BYTE -> member.asByte();
            case SHORT -> member.asShort();
            case INT -> member.asInt();
            case LONG -> member.asLong();
            case FLOAT -> member.asFloat();
            case DOUBLE -> member.asDouble();
            case CHAR -> member.asChar();
            case STRING -> member.asString();
            case ENUM -> enumOf(member, classLoader);
            case CLASS -> classOf(member, classLoader);
            case NESTED_ANNOTATION -> annotationOf(member.asNestedAnnotation(), classLoader);
            case ARRAY -> arrayOf(member, type, name, classLoader);
        };
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object enumOf(AnnotationMember member, ClassLoader classLoader) {
        try {
            Class<? extends Enum> enumType = (Class<? extends Enum>) Class.forName(
                member.asEnumClass().name(), false, classLoader);
            return Enum.valueOf(enumType, member.asEnumConstant());
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException("The enum " + member.asEnumClass().name()
                + " is not loadable here", e);
        }
    }

    private static Object classOf(AnnotationMember member, ClassLoader classLoader) {
        if (member.asType() instanceof jakarta.enterprise.lang.model.types.ClassType classType) {
            try {
                return Class.forName(classType.declaration().name(), false, classLoader);
            } catch (ClassNotFoundException e) {
                throw new IllegalArgumentException("The class " + classType.declaration().name()
                    + " is not loadable here", e);
            }
        }
        throw new IllegalArgumentException("The class member " + member + " does not name a class");
    }

    private static Object arrayOf(AnnotationMember member, Class<? extends Annotation> type, String name,
                                  ClassLoader classLoader) {
        List<AnnotationMember> elements = member.asArray();
        Class<?> componentType = componentTypeOf(type, name);
        Object array = java.lang.reflect.Array.newInstance(componentType, elements.size());
        for (int i = 0; i < elements.size(); i++) {
            java.lang.reflect.Array.set(array, i, valueOf(elements.get(i), type, name, classLoader));
        }
        return array;
    }

    private static Class<?> componentTypeOf(Class<? extends Annotation> type, String name) {
        try {
            return type.getDeclaredMethod(name).getReturnType().getComponentType();
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException("The annotation " + type.getName() + " has no member "
                + name, e);
        }
    }

    /**
     * Answers each member of the built annotation from what was composed, or from the member's default.
     *
     * @param type    The annotation type
     * @param members The composed members
     */
    private record MemberAnswers(Class<? extends Annotation> type, Map<String, Object> members)
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
                        return annotationHashCode(proxy);
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
            return method.getDefaultValue();
        }

        private boolean annotationEquals(Object proxy, Object other) throws Throwable {
            if (!(other instanceof Annotation annotation) || !type.equals(annotation.annotationType())) {
                return false;
            }
            for (Method member : type.getDeclaredMethods()) {
                Object mine = invoke(proxy, member, new Object[0]);
                Object theirs = member.invoke(annotation);
                boolean same = mine != null && mine.getClass().isArray()
                    ? java.util.Arrays.deepEquals(new Object[]{mine}, new Object[]{theirs})
                    : java.util.Objects.equals(mine, theirs);
                if (!same) {
                    return false;
                }
            }
            return true;
        }

        private int annotationHashCode(Object proxy) throws Throwable {
            int hash = 0;
            for (Method member : type.getDeclaredMethods()) {
                Object value = invoke(proxy, member, new Object[0]);
                int valueHash;
                if (value == null) {
                    valueHash = 0;
                } else if (value instanceof Object[] objects) {
                    valueHash = java.util.Arrays.hashCode(objects);
                } else if (value instanceof int[] ints) {
                    valueHash = java.util.Arrays.hashCode(ints);
                } else if (value instanceof long[] longs) {
                    valueHash = java.util.Arrays.hashCode(longs);
                } else if (value instanceof short[] shorts) {
                    valueHash = java.util.Arrays.hashCode(shorts);
                } else if (value instanceof byte[] bytes) {
                    valueHash = java.util.Arrays.hashCode(bytes);
                } else if (value instanceof char[] chars) {
                    valueHash = java.util.Arrays.hashCode(chars);
                } else if (value instanceof boolean[] booleans) {
                    valueHash = java.util.Arrays.hashCode(booleans);
                } else if (value instanceof float[] floats) {
                    valueHash = java.util.Arrays.hashCode(floats);
                } else if (value instanceof double[] doubles) {
                    valueHash = java.util.Arrays.hashCode(doubles);
                } else {
                    valueHash = value.hashCode();
                }
                hash += (127 * member.getName().hashCode()) ^ valueHash;
            }
            return hash;
        }
    }
}
