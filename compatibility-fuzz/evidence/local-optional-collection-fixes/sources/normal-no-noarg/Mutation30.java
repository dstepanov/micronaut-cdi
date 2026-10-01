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
public class Mutation30 {
@ApplicationScoped public static class A { @Inject public A(BeanManager m){} public void x(){} } @Dependent public static class Consumer { @Inject A a; public String run(){a.x();return "ok";} }
}