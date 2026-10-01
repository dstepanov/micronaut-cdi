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
public class Mutation53 {
public static int heard; public static class Base {public void observe(@Observes String s){heard++;}} @Dependent public static class Consumer extends Base { @Inject BeanManager bm; public void observe(String s){heard+=100;} public String run(){bm.getEvent().select(String.class).fire("event");return "heard="+heard;}}
}