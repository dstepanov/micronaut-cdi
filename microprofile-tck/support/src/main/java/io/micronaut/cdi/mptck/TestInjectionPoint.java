/* Licensed under the Apache License, Version 2.0. */
package io.micronaut.cdi.mptck;

import jakarta.enterprise.inject.Default;
import jakarta.enterprise.inject.spi.*;
import jakarta.inject.Qualifier;
import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.*;

/** Reflection is confined to the unmanaged Arquillian test, not the application under test. */
final class TestInjectionPoint implements InjectionPoint {
    private final Member member;
    private final Type type;
    private final Annotated annotated;
    private final Set<Annotation> qualifiers;
    private TestInjectionPoint(Member member, Type type, Annotated annotated) {
        this.member = member;
        this.type = type;
        this.annotated = annotated;
        Set<Annotation> found = new LinkedHashSet<>();
        for (Annotation annotation : annotated.getAnnotations()) {
            if (annotation.annotationType().isAnnotationPresent(Qualifier.class)) found.add(annotation);
        }
        if (found.isEmpty() || found.stream().allMatch(a -> a instanceof jakarta.inject.Named)) found.add(Default.Literal.INSTANCE);
        qualifiers = Collections.unmodifiableSet(found);
    }
    static TestInjectionPoint of(Field field) { return new TestInjectionPoint(field, field.getGenericType(), new TestField<>(field)); }
    static TestInjectionPoint of(Method method, int position) {
        var callable = new TestMethod<>(method);
        return new TestInjectionPoint(method, method.getGenericParameterTypes()[position], callable.getParameters().get(position));
    }
    public Type getType() { return type; }
    public Set<Annotation> getQualifiers() { return qualifiers; }
    public Bean<?> getBean() { return null; }
    public Member getMember() { return member; }
    public Annotated getAnnotated() { return annotated; }
    public boolean isDelegate() { return annotated.isAnnotationPresent(jakarta.decorator.Delegate.class); }
    public boolean isTransient() { return member instanceof Field && Modifier.isTransient(member.getModifiers()); }
    @Override public String toString() { return "TCK injection point " + member + " : " + type; }

    private abstract static class Metadata implements Annotated {
        final Type type;
        final Set<Annotation> annotations;
        Metadata(Type type, Annotation[] annotations) { this.type = type; this.annotations = Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(annotations))); }
        public Type getBaseType() { return type; }
        public Set<Type> getTypeClosure() {
            Set<Type> closure = new LinkedHashSet<>();
            closure.add(type);
            Class<?> raw = type instanceof Class<?> c ? c : type instanceof ParameterizedType p ? (Class<?>) p.getRawType() : null;
            if (raw != null) {
                for (Class<?> c = raw; c != null; c = c.getSuperclass()) { closure.add(c); closure.addAll(Arrays.asList(c.getGenericInterfaces())); }
            }
            closure.add(Object.class);
            return Collections.unmodifiableSet(closure);
        }
        public <T extends Annotation> T getAnnotation(Class<T> type) { return annotations.stream().filter(type::isInstance).map(type::cast).findFirst().orElse(null); }
        public Set<Annotation> getAnnotations() { return annotations; }
        public <T extends Annotation> Set<T> getAnnotations(Class<T> type) {
            Set<T> found = new LinkedHashSet<>();
            for (Annotation annotation : annotations) if (type.isInstance(annotation)) found.add(type.cast(annotation));
            return Collections.unmodifiableSet(found);
        }
        public boolean isAnnotationPresent(Class<? extends Annotation> type) { return getAnnotation(type) != null; }
    }
    private static final class TestType<X> extends Metadata implements AnnotatedType<X> {
        private final Class<X> clazz;
        TestType(Class<X> clazz) { super(clazz, clazz.getAnnotations()); this.clazz = clazz; }
        public Class<X> getJavaClass() { return clazz; }
        @SuppressWarnings({"unchecked", "rawtypes"}) public Set<AnnotatedConstructor<X>> getConstructors() {
            Set<AnnotatedConstructor<X>> values = new LinkedHashSet<>();
            for (Constructor<?> constructor : clazz.getDeclaredConstructors()) values.add(new TestConstructor(constructor));
            return Collections.unmodifiableSet(values);
        }
        @SuppressWarnings({"unchecked", "rawtypes"}) public Set<AnnotatedMethod<? super X>> getMethods() {
            Set<AnnotatedMethod<? super X>> values = new LinkedHashSet<>();
            for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
                for (Method method : c.getDeclaredMethods()) values.add(new TestMethod(method));
            }
            return Collections.unmodifiableSet(values);
        }
        @SuppressWarnings({"unchecked", "rawtypes"}) public Set<AnnotatedField<? super X>> getFields() {
            Set<AnnotatedField<? super X>> values = new LinkedHashSet<>();
            for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field field : c.getDeclaredFields()) values.add(new TestField(field));
            }
            return Collections.unmodifiableSet(values);
        }
    }
    private static final class TestField<X> extends Metadata implements AnnotatedField<X> {
        private final Field field;
        TestField(Field field) { super(field.getGenericType(), field.getAnnotations()); this.field = field; }
        public Field getJavaMember() { return field; }
        public boolean isStatic() { return Modifier.isStatic(field.getModifiers()); }
        @SuppressWarnings("unchecked") public AnnotatedType<X> getDeclaringType() { return new TestType<>((Class<X>) field.getDeclaringClass()); }
    }
    private abstract static class TestCallable<X> extends Metadata implements AnnotatedCallable<X> {
        final Executable executable;
        TestCallable(Type type, Executable executable) { super(type, executable.getAnnotations()); this.executable = executable; }
        public boolean isStatic() { return Modifier.isStatic(executable.getModifiers()); }
        @SuppressWarnings("unchecked") public AnnotatedType<X> getDeclaringType() { return new TestType<>((Class<X>) executable.getDeclaringClass()); }
        public List<AnnotatedParameter<X>> getParameters() {
            List<AnnotatedParameter<X>> parameters = new ArrayList<>();
            for (int i = 0; i < executable.getParameterCount(); i++) parameters.add(new TestParameter<>(this, i));
            return Collections.unmodifiableList(parameters);
        }
    }
    private static final class TestMethod<X> extends TestCallable<X> implements AnnotatedMethod<X> {
        TestMethod(Method method) { super(method.getGenericReturnType(), method); }
        public Method getJavaMember() { return (Method) executable; }
    }
    private static final class TestConstructor<X> extends TestCallable<X> implements AnnotatedConstructor<X> {
        TestConstructor(Constructor<X> constructor) { super(constructor.getDeclaringClass(), constructor); }
        @SuppressWarnings("unchecked") public Constructor<X> getJavaMember() { return (Constructor<X>) executable; }
    }
    private static final class TestParameter<X> extends Metadata implements AnnotatedParameter<X> {
        private final TestCallable<X> callable;
        private final int position;
        TestParameter(TestCallable<X> callable, int position) {
            super(callable.executable.getGenericParameterTypes()[position], callable.executable.getParameterAnnotations()[position]);
            this.callable = callable; this.position = position;
        }
        public int getPosition() { return position; }
        public AnnotatedCallable<X> getDeclaringCallable() { return callable; }
    }
}
