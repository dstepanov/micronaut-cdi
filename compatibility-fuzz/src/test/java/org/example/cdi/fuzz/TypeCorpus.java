package org.example.cdi.fuzz;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.util.TypeLiteral;
import java.lang.reflect.Type;
import java.util.*;
import java.io.Serializable;
public class TypeCorpus {
    public interface View<T> {}
    public static class Box<T> implements View<T> {}
    @Dependent public static class P0 { @Produces public Box<String> value() { return new Box<String>(); } }
    @Dependent public static class P1 { @Produces public Box<Integer> value() { return new Box<Integer>(); } }
    @Dependent public static class P2 { @Produces public Box<Number> value() { return new Box<Number>(); } }
    @Dependent public static class P3 { @Produces public Box<Object> value() { return new Box<Object>(); } }
    @Dependent public static class P4 { @Produces public List<String> value() { return new ArrayList<>(); } }
    @Dependent public static class P5 { @Produces public List<Integer> value() { return new ArrayList<>(); } }
    @Dependent public static class P6 { @Produces public List<Number> value() { return new ArrayList<>(); } }
    @Dependent public static class P7 { @Produces public List<Object> value() { return new ArrayList<>(); } }
    @Dependent public static class P8 { @Produces public ArrayList<String> value() { return new ArrayList<>(); } }
    @Dependent public static class P9 { @Produces public Box<List<String>> value() { return new Box<>(); } }
    @Dependent public static class P10 { @Produces public Box<String[]> value() { return new Box<>(); } }
    @Dependent public static class P11 { @Produces public String[] value() { return new String[0]; } }
    @Dependent public static class P12 { @Produces public List<String>[] value() { return null; } }
    @Dependent public static class P13 { @Produces public Box value() { return new Box(); } }
    @Dependent public static class P14 { @Produces public List value() { return new ArrayList(); } }
    @Dependent public static class P15 { @Produces public int value() { return 7; } }
    @Dependent public static class P16 { @Produces public Integer value() { return 7; } }
    @Dependent public static class Unbounded<T> extends Box<T> {}
    @Dependent public static class Bounded<T extends Number> extends Box<T> {}
    @Dependent public static class Recursive<T extends Comparable<T>> extends Box<T> {}
    @Dependent public static class Multi<T extends Number & Comparable<T>> extends Box<T> {}
    @Dependent public static class Nested<T> extends Box<List<T>> {}
    public static final Class<?>[] PRODUCERS = { P0.class, P1.class, P2.class, P3.class, P4.class, P5.class, P6.class, P7.class, P8.class, P9.class, P10.class, P11.class, P12.class, P13.class, P14.class, P15.class, P16.class, Unbounded.class, Bounded.class, Recursive.class, Multi.class, Nested.class };
    public static final Type[] QUERIES = {
        String.class,
        Integer.class,
        Number.class,
        Object.class,
        int.class,
        Serializable.class,
        Cloneable.class,
        String[].class,
        Object[].class,
        new TypeLiteral<List<String>[]>() {}.getType(),
        new TypeLiteral<List<?>[]>() {}.getType(),
        Box.class,
        new TypeLiteral<Box<String>>() {}.getType(),
        new TypeLiteral<Box<Integer>>() {}.getType(),
        new TypeLiteral<Box<Number>>() {}.getType(),
        new TypeLiteral<Box<Object>>() {}.getType(),
        new TypeLiteral<Box<?>>() {}.getType(),
        new TypeLiteral<Box<? extends Number>>() {}.getType(),
        new TypeLiteral<Box<? super Integer>>() {}.getType(),
        new TypeLiteral<Box<? extends CharSequence>>() {}.getType(),
        new TypeLiteral<Box<List<String>>>() {}.getType(),
        new TypeLiteral<Box<List<?>>>() {}.getType(),
        new TypeLiteral<Box<String[]>>() {}.getType(),
        View.class,
        new TypeLiteral<View<String>>() {}.getType(),
        new TypeLiteral<View<Integer>>() {}.getType(),
        new TypeLiteral<View<Number>>() {}.getType(),
        new TypeLiteral<View<Object>>() {}.getType(),
        new TypeLiteral<View<?>>() {}.getType(),
        new TypeLiteral<View<? extends Number>>() {}.getType(),
        new TypeLiteral<View<? super Integer>>() {}.getType(),
        new TypeLiteral<View<? extends CharSequence>>() {}.getType(),
        new TypeLiteral<View<List<String>>>() {}.getType(),
        new TypeLiteral<View<List<?>>>() {}.getType(),
        new TypeLiteral<View<String[]>>() {}.getType(),
        List.class,
        new TypeLiteral<List<String>>() {}.getType(),
        new TypeLiteral<List<Integer>>() {}.getType(),
        new TypeLiteral<List<Number>>() {}.getType(),
        new TypeLiteral<List<Object>>() {}.getType(),
        new TypeLiteral<List<?>>() {}.getType(),
        new TypeLiteral<List<? extends Number>>() {}.getType(),
        new TypeLiteral<List<? super Integer>>() {}.getType(),
        new TypeLiteral<List<? extends CharSequence>>() {}.getType(),
        new TypeLiteral<List<List<String>>>() {}.getType(),
        new TypeLiteral<List<List<?>>>() {}.getType(),
        new TypeLiteral<List<String[]>>() {}.getType(),
        Collection.class,
        new TypeLiteral<Collection<String>>() {}.getType(),
        new TypeLiteral<Collection<Integer>>() {}.getType(),
        new TypeLiteral<Collection<Number>>() {}.getType(),
        new TypeLiteral<Collection<Object>>() {}.getType(),
        new TypeLiteral<Collection<?>>() {}.getType(),
        new TypeLiteral<Collection<? extends Number>>() {}.getType(),
        new TypeLiteral<Collection<? super Integer>>() {}.getType(),
        new TypeLiteral<Collection<? extends CharSequence>>() {}.getType(),
        new TypeLiteral<Collection<List<String>>>() {}.getType(),
        new TypeLiteral<Collection<List<?>>>() {}.getType(),
        new TypeLiteral<Collection<String[]>>() {}.getType(),
        ArrayList.class,
        new TypeLiteral<ArrayList<String>>() {}.getType(),
        new TypeLiteral<ArrayList<Integer>>() {}.getType(),
        new TypeLiteral<ArrayList<Number>>() {}.getType(),
        new TypeLiteral<ArrayList<Object>>() {}.getType(),
        new TypeLiteral<ArrayList<?>>() {}.getType(),
        new TypeLiteral<ArrayList<? extends Number>>() {}.getType(),
        new TypeLiteral<ArrayList<? super Integer>>() {}.getType(),
        new TypeLiteral<ArrayList<? extends CharSequence>>() {}.getType(),
        new TypeLiteral<ArrayList<List<String>>>() {}.getType(),
        new TypeLiteral<ArrayList<List<?>>>() {}.getType(),
        new TypeLiteral<ArrayList<String[]>>() {}.getType(),
        new TypeLiteral<Box<? super Number>>() {}.getType(),
        new TypeLiteral<View<? super Number>>() {}.getType(),
    };
}
