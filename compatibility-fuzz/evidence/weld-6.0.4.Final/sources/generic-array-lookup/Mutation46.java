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
public class Mutation46 {
@Dependent public static class A {@Produces List<String>[] p(){return new List[]{List.of("produced")};}} @Dependent public static class Consumer {@Inject Instance<List<String>[]> arrays; public String run(){return Arrays.deepToString(arrays.get());}}
}