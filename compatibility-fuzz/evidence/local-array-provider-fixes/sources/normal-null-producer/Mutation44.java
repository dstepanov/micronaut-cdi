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
public class Mutation44 {
@Dependent public static class A {@Produces @ApplicationScoped Runnable p(){return null;}} @Dependent public static class Consumer {@Inject Runnable r; public String run(){r.run();return "ok";}}
}