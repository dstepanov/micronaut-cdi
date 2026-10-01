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
public class Mutation12 {
@Dependent public abstract static class A { @Inject abstract void init(BeanManager m); } @Dependent public static class Consumer extends A {void init(BeanManager m){} public String run(){return "ok";} }
}