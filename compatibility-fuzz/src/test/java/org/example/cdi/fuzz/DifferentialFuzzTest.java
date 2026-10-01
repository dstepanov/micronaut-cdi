package org.example.cdi.fuzz;

import io.micronaut.cdi.se.MicronautSeContainerInitializer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.se.SeContainer;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.util.AnnotationLiteral;
import jakarta.enterprise.util.Nonbinding;
import jakarta.enterprise.util.TypeLiteral;
import jakarta.inject.Inject;
import jakarta.inject.Qualifier;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InterceptorBinding;
import jakarta.interceptor.InvocationContext;
import org.jboss.weld.environment.se.Weld;
import org.junit.jupiter.api.Test;

import java.io.Serializable;
import java.lang.annotation.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Bounded grammar fuzzing and seeded state-machine sequences, comparing identical compiled fixtures. */
public class DifferentialFuzzTest {
    static final long SEED = Long.getLong("fuzz.seed", 0xCD140L);
    static final List<String> ROWS = new ArrayList<>();
    static final List<String> CALLS = new ArrayList<>();
    static Function<InvocationContext, Object> mutation;
    static String mode;

    interface Probe { List<String> run(SeContainer c) throws Exception; }

    static SeContainer open(boolean reference, Class<?>... classes) {
        return reference
            ? new Weld().disableDiscovery().addBeanClasses(classes).initialize()
            : new MicronautSeContainerInitializer().disableDiscovery().addBeanClasses(classes).initialize();
    }

    static List<String> capture(boolean reference, Probe probe, Class<?>... classes) {
        CALLS.clear();
        try (SeContainer c = open(reference, classes)) {
            return probe.run(c);
        } catch (Exception | LinkageError e) {
            return List.of("ERROR:" + root(e).getClass().getSimpleName() + ":" + root(e).getMessage());
        }
    }

    static Throwable root(Throwable e) {
        while (e.getCause() != null && (e instanceof InvocationTargetException || e instanceof java.util.concurrent.CompletionException)) e = e.getCause();
        return e;
    }

    static String outcome(Throwing action) {
        try { return "OK:" + value(action.run()); }
        catch (Throwable e) { return "ERROR:" + root(e).getClass().getSimpleName(); }
    }
    interface Throwing { Object run() throws Throwable; }
    static String value(Object v) {
        if (v == null) return "null";
        if (v instanceof Collection<?> col) return col.stream().map(DifferentialFuzzTest::value).toList().toString();
        if (v.getClass().isArray()) {
            List<String> a = new ArrayList<>();
            for (int i = 0; i < Array.getLength(v); i++) a.add(value(Array.get(v, i)));
            return a.toString();
        }
        if (v.getClass() == Object.class) return "Object";
        return v.toString();
    }
    static void row(String family, String id, String ref, String actual) {
        ROWS.add(String.join("\t", family, id, clean(ref), clean(actual), Objects.equals(ref, actual) ? "MATCH" : "DIFF"));
    }
    static String clean(String s) { return s.replace("\t", " ").replace("\n", " ").replace("\r", " "); }
    static void compare(String family, Probe probe, Class<?>... classes) {
        List<String> ref = capture(true, probe, classes), actual = capture(false, probe, classes);
        int n = Math.max(ref.size(), actual.size());
        for (int i = 0; i < n; i++) row(family, Arrays.toString(classes) + "/" + i,
            i < ref.size() ? ref.get(i) : "MISSING", i < actual.size() ? actual.get(i) : "MISSING");
    }

    @Test void campaign() throws Exception {
        ROWS.clear();
        types();
        qualifiers();
        events();
        parameters();
        handles();
        injection();
        Path out = Path.of(System.getProperty("fuzz.output"));
        Files.createDirectories(out.getParent());
        Files.write(out, ROWS);
        System.out.println("FUZZ seed=" + SEED + " comparisons=" + ROWS.size() + " differences=" + ROWS.stream().filter(s -> s.endsWith("DIFF")).count());
        assertTrue(ROWS.size() > 2000, "the corpus must actually execute");
    }

    static void types() {
        for (Class<?> producer : TypeCorpus.PRODUCERS) {
            Probe probe = c -> {
                List<String> results = new ArrayList<>();
                for (Type query : TypeCorpus.QUERIES) results.add(outcome(() -> c.getBeanManager().getBeans(query, Any.Literal.INSTANCE)
                    .stream().filter(b -> b.getBeanClass().getName().startsWith("org.example.cdi.fuzz.")).count()));
                return results;
            };
            List<String> ref = capture(true, probe, producer), actual = capture(false, probe, producer);
            assertEquals(TypeCorpus.QUERIES.length, ref.size(), "reference deployment failed: " + producer);
            assertEquals(TypeCorpus.QUERIES.length, actual.size(), "Micronaut deployment failed: " + producer);
            for (int i = 0; i < TypeCorpus.QUERIES.length; i++) row("types", producer.getSimpleName()+" -> "+TypeCorpus.QUERIES[i].getTypeName(),
                ref.get(Math.min(i,ref.size()-1)), actual.get(Math.min(i,actual.size()-1)));
            Probe metadata = c -> c.getBeanManager().getBeans(Object.class, Any.Literal.INSTANCE).stream()
                .filter(b -> b.getBeanClass().equals(producer))
                .map(b -> b.getTypes().stream().map(DifferentialFuzzTest::typeName).sorted().toList().toString())
                .sorted().toList();
            row("bean-types", producer.getSimpleName(), capture(true, metadata, producer).toString(), capture(false, metadata, producer).toString());
        }
    }

    static String typeName(Type t) {
        if (t instanceof Class<?> cls) return cls.isArray() ? typeName(cls.getComponentType())+"[]" : cls.getName();
        if (t instanceof GenericArrayType a) return typeName(a.getGenericComponentType())+"[]";
        if (t instanceof ParameterizedType p) return typeName(p.getRawType())+"<"+String.join(",",Arrays.stream(p.getActualTypeArguments()).map(DifferentialFuzzTest::typeName).toList())+">";
        if (t instanceof TypeVariable<?> v) return v.getName();
        if (t instanceof WildcardType w) return Arrays.toString(w.getUpperBounds())+"/"+Arrays.toString(w.getLowerBounds());
        return t.getTypeName();
    }

    @Qualifier @Retention(RetentionPolicy.RUNTIME) @Target({ElementType.TYPE,ElementType.FIELD,ElementType.PARAMETER,ElementType.METHOD})
    public @interface Mark { String value() default "a"; int rank() default 1; Class<?> type() default String.class; @Nonbinding String note() default ""; }
    public static class MarkLiteral extends AnnotationLiteral<Mark> implements Mark {
        final String v, n; final int r; final Class<?> t;
        MarkLiteral(String v, int r, Class<?> t, String n) { this.v=v;this.r=r;this.t=t;this.n=n; }
        public String value(){return v;} public int rank(){return r;} public Class<?> type(){return t;} public String note(){return n;}
    }
    @Dependent public static class Qualified {
        @Produces @Mark String a(){return "a";}
        @Produces @Mark(value="b", rank=2, type=Integer.class, note="bean") String b(){return "b";}
        @Produces String plain(){return "plain";}
    }
    static void qualifiers() {
        Probe probe = c -> {
            List<String> results = new ArrayList<>();
            Random r = new Random(SEED);
            for(int i=0;i<160;i++) {
                String v = List.of("a","b","c").get(r.nextInt(3));
                int rank = r.nextInt(3);
                Class<?> t = r.nextBoolean()?String.class:Integer.class;
                Mark m = new MarkLiteral(v,rank,t,r.nextBoolean()?"bean":"different");
                results.add(outcome(() -> c.select(String.class,m).stream().sorted().toList()));
                results.add(outcome(() -> c.getBeanManager().getBeans(String.class,m).size()));
            }
            results.add(outcome(() -> c.select(String.class).get()));
            results.add(outcome(() -> c.select(String.class,Any.Literal.INSTANCE).isAmbiguous()));
            results.add(outcome(() -> c.select(String.class,new MarkLiteral("a",1,String.class,"")).select(new MarkLiteral("b",2,Integer.class,"")).isUnsatisfied()));
            return results;
        };
        compare("qualifiers",probe,Qualified.class);
    }

    @Dependent public static class Observers {
        void a(@Observes @Priority(110) TypeCorpus.Box<String> x){CALLS.add("box-string");}
        void b(@Observes @Priority(120) TypeCorpus.Box<Integer> x){CALLS.add("box-int");}
        void c(@Observes @Priority(130) TypeCorpus.Box<?> x){CALLS.add("box-any");}
        void d(@Observes @Priority(140) TypeCorpus.View<String> x){CALLS.add("view-string");}
        void e(@Observes @Priority(150) TypeCorpus.View<? extends Number> x){CALLS.add("view-number");}
        void f(@Observes @Priority(160) Object x){if(x instanceof TypeCorpus.Box || x instanceof List) CALLS.add("object");}
        void g(@Observes @Priority(170) List<String> x){CALLS.add("list-string");}
        void h(@Observes @Priority(180) List<?> x){CALLS.add("list-any");}
        void i(@Observes @Priority(190) TypeCorpus.Box<List<String>> x){CALLS.add("box-list");}
    }
    static void events() {
        compare("events", c -> {
            List<String> result=new ArrayList<>();
            for(int i=0;i<8;i++) {
                CALLS.clear(); final int choice=i;
                String status=outcome(()->{
                    var e=c.getBeanManager().getEvent();
                    switch(choice) {
                        case 0 -> e.select(new TypeLiteral<TypeCorpus.Box<String>>(){}).fire(new TypeCorpus.Box<>());
                        case 1 -> e.select(new TypeLiteral<TypeCorpus.Box<Integer>>(){}).fire(new TypeCorpus.Box<>());
                        case 2 -> e.select(new TypeLiteral<TypeCorpus.Box<List<String>>>(){}).fire(new TypeCorpus.Box<>());
                        case 3 -> e.select(new TypeLiteral<TypeCorpus.View<String>>(){}).fire(new TypeCorpus.Box<String>());
                        case 4 -> e.select(new TypeLiteral<List<String>>(){}).fire(new ArrayList<>());
                        case 5 -> e.select(new TypeLiteral<List<Integer>>(){}).fire(new ArrayList<>());
                        case 6 -> e.fire(new TypeCorpus.Box<String>());
                        case 7 -> e.select(new TypeLiteral<TypeCorpus.Box<?>>(){}).fire(new TypeCorpus.Box<String>());
                    }
                    return CALLS;
                });
                result.add(status);
            }
            return result;
        }, Observers.class);
    }

    @InterceptorBinding @Retention(RetentionPolicy.RUNTIME) @Target({ElementType.TYPE,ElementType.METHOD,ElementType.CONSTRUCTOR})
    public @interface Traced {}
    @Interceptor @Traced @Priority(200) public static class Mutator {
        @AroundInvoke Object invoke(InvocationContext c) throws Exception {
            if (mutation != null) return mutation.apply(c);
            return c.proceed();
        }
    }
    @Dependent @Traced public static class Service {
        public byte pByte(byte x){return x;} public short pShort(short x){return x;}
        public char pChar(char x){return x;} public int pInt(int x){return x;}
        public long pLong(long x){return x;} public float pFloat(float x){return x;}
        public double pDouble(double x){return x;} public boolean pBoolean(boolean x){return x;}
        public String pString(String x){return x;} public Number pNumber(Number x){return x;}
        public Object pObject(Object x){return x;} public int[] pInts(int[] x){return x;}
        public String[] pStrings(String[] x){return x;} public List<String> pList(List<String> x){return x;}
        public String none(){return "none";}
    }
    static Object initial(Class<?> t) {
        if(t==byte.class)return (byte)1;if(t==short.class)return(short)1;if(t==char.class)return 'a';
        if(t==int.class)return 1;if(t==long.class)return 1L;if(t==float.class)return 1F;if(t==double.class)return 1D;
        if(t==boolean.class)return true;if(t==String.class)return "initial";if(t==Number.class)return 1;
        if(t==int[].class)return new int[]{1};if(t==String[].class)return new String[]{"initial"};
        if(t==List.class)return List.of("initial");return "initial";
    }
    static void parameters() {
        List<Method> methods=Arrays.stream(Service.class.getDeclaredMethods()).filter(m->m.getName().startsWith("p")).sorted(Comparator.comparing(Method::getName)).toList();
        Object[] replacements={(byte)2,(short)2,'z',2,2L,2F,2D,true,"changed",null,new Object(),new int[]{2},new String[]{"changed"},List.of("changed")};
        Probe probe=c->{
            Service service=c.select(Service.class).get();List<String> result=new ArrayList<>();
            for(Method method:methods) for(Object replacement:replacements) {
                mutation=ctx->{
                    try {ctx.setParameters(new Object[]{replacement});}
                    catch(IllegalArgumentException e){return "REJECT";}
                    try {return "ACCEPT:"+value(ctx.proceed());}
                    catch(Exception e){return "PROCEED_ERROR:"+root(e).getClass().getSimpleName();}
                };
                result.add(outcome(()->method.invoke(service,initial(method.getParameterTypes()[0]))));
            }
            mutation=ctx->{Object[] a=ctx.getParameters();a[0]="mutated-read";try{return ctx.proceed();}catch(Exception e){throw new RuntimeException(e);}};
            result.add(outcome(()->service.pString("original")));
            mutation=ctx->{Object[] a={"before"};ctx.setParameters(a);a[0]="after";try{return ctx.proceed();}catch(Exception e){throw new RuntimeException(e);}};
            result.add(outcome(()->service.pString("original")));
            mutation=ctx->{ctx.setParameters(new Object[0]);try{return ctx.proceed();}catch(Exception e){throw new RuntimeException(e);}};
            result.add(outcome(service::none));
            mutation=null;return result;
        };
        List<String> ref=capture(true,probe,Service.class,Mutator.class),actual=capture(false,probe,Service.class,Mutator.class);
        assertEquals(methods.size()*replacements.length+3,ref.size(), "reference interceptor setup failed");
        assertEquals(ref.size(),actual.size(), "Micronaut interceptor setup failed");
        int n=0;
        for(Method m:methods)for(Object x:replacements) {
            row("parameters",m.getName()+" <- "+(x==null?"null":x.getClass().getTypeName())+"("+value(x)+")",ref.get(Math.min(n,ref.size()-1)),actual.get(Math.min(n,actual.size()-1)));n++;
        }
        for(;n<ref.size()||n<actual.size();n++)row("parameter-array",Integer.toString(n),ref.get(Math.min(n,ref.size()-1)),actual.get(Math.min(n,actual.size()-1)));
    }

    @Dependent public static class Child {
        @PreDestroy void destroy(){CALLS.add("child-destroy");}
    }
    @Dependent public static class Parent {
        @Inject Child child;
        @PostConstruct void init(){CALLS.add("parent-create");}
        @PreDestroy void destroy(){CALLS.add("parent-destroy");}
    }
    static void handles() {
        compare("handles",c->{
            List<String> result=new ArrayList<>();Random r=new Random(SEED);
            for(int i=0;i<64;i++) {
                CALLS.clear();Instance.Handle<Parent> h=c.select(Parent.class).getHandle();
                StringBuilder sequence=new StringBuilder();
                for(int j=0;j<5;j++) {
                    int op=r.nextInt(4);sequence.append(op).append('=');
                    sequence.append(outcome(()->{switch(op){case 0:h.get();return "get";case 1:h.destroy();return "destroy";case 2:h.close();return "close";default:return h.getBean().getScope().getSimpleName();}})).append(';');
                }
                h.destroy();result.add(sequence+CALLS.toString());
            }
            return result;
        },Parent.class,Child.class);
    }

    @Dependent @Mark public static class OnlyQualified implements Serializable {}
    @Dependent public static class UnqualifiedConsumer { @Inject OnlyQualified x; public String read(){return x==null?"null":"injected";} }
    @Dependent public static class Lists {
        @Produces List<String> words(){return List.of("produced");}
        @Produces String element(){return "element";}
    }
    @Dependent public static class ListConsumer { @Inject List<String> words; public String read(){return words.toString();} }
    static void injection() {
        compare("injection-default",c->List.of(outcome(()->c.select(UnqualifiedConsumer.class).get().read())),OnlyQualified.class,UnqualifiedConsumer.class);
        compare("injection-list",c->List.of(outcome(()->c.select(ListConsumer.class).get().read())),Lists.class,ListConsumer.class);
    }
}
