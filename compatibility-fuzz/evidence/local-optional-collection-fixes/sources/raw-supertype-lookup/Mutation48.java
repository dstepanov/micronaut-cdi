package org.example.cdi.fuzz.generated;
import jakarta.enterprise.context.*;
import jakarta.enterprise.event.*;
import jakarta.enterprise.inject.*;
import jakarta.enterprise.inject.spi.*;
import jakarta.enterprise.util.*;
import jakarta.inject.*;
import jakarta.annotation.*;
import jakarta.interceptor.*;
import java.lang.annotation.*;
import java.util.*;
public class Mutation48 {
public interface View<T>{} public static class Box<T> implements View<T>{} @Dependent public static class A {@Produces Box p(){return new Box();}} @Dependent public static class Consumer {@Inject Instance<View<String>> views; public String run(){return "unsatisfied="+views.isUnsatisfied();}}
}