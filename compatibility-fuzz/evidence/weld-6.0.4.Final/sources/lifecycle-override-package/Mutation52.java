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
public class Mutation52 {
public static final List<String> LOG=new ArrayList<>(); public static class Base {@PostConstruct  void init(){LOG.add("base");}} @Dependent public static class Consumer extends Base { void init(){LOG.add("sub");} public String run(){return LOG.toString();}}
}