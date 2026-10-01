package org.example.cdi.fuzz;

import io.micronaut.cdi.se.MicronautSeContainerInitializer;
import jakarta.enterprise.inject.se.SeContainer;
import org.jboss.weld.environment.se.Weld;
import org.junit.jupiter.api.Test;
import javax.tools.*;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Compile each mutation separately; compare acceptance at compilation/deployment, then runtime when valid. */
public class SourceMutationTest {
    record Case(String id, String body, String... beans) {}
    static final String IMPORTS = """
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
        """;
    static List<Case> cases() {
        List<Case> c = new ArrayList<>();
        c.add(new Case("missing-injection", "@Dependent public static class Consumer { @Inject Runnable x; public String run(){return \"reached\";} }", "Consumer"));
        c.add(new Case("ambiguous-injection", "@Dependent public static class A implements Runnable {public void run(){}} @Dependent public static class B implements Runnable {public void run(){}} @Dependent public static class Consumer { @Inject Runnable x; public String run(){return \"reached\";} }", "A","B","Consumer"));
        c.add(new Case("missing-observer-injection", "@Dependent public static class A { void hear(@Observes String event, Runnable missing){} }", "A"));
        c.add(new Case("missing-disposer-injection", "@Dependent public static class A { @Produces String value(){return \"produced\";} void dispose(@Disposes String value,Runnable missing){} }", "A"));
        c.add(new Case("duplicate-bean-name", "@Dependent @Named(\"same\") public static class A {} @Dependent @Named(\"same\") public static class B {}", "A","B"));
        c.add(new Case("prefix-bean-name", "@Dependent @Named(\"same\") public static class A {} @Dependent @Named(\"same.child\") public static class B {}", "A","B"));
        c.add(new Case("two-scopes", "@Dependent @ApplicationScoped public static class A {}", "A"));
        c.add(new Case("two-inject-constructors", "@Dependent public static class A { @Inject public A(){} @Inject public A(BeanManager m){} }", "A"));
        c.add(new Case("inject-final-field", "@Dependent public static class Consumer { @Inject final BeanManager x=null; public String run(){return x==null?\"null\":\"injected\";} }", "Consumer"));
        c.add(new Case("inject-static-field", "@Dependent public static class Consumer { @Inject static BeanManager x; public String run(){return x==null?\"null\":\"injected\";} }", "Consumer"));
        c.add(new Case("inject-static-initializer", "@Dependent public static class Consumer { static boolean set; @Inject static void init(BeanManager m){set=true;} public String run(){return \"set=\"+set;} }", "Consumer"));
        c.add(new Case("inject-generic-initializer", "@Dependent public static class A { @Inject <T> void init(BeanManager m){} }", "A"));
        c.add(new Case("inject-abstract-initializer", "@Dependent public abstract static class A { @Inject abstract void init(BeanManager m); } @Dependent public static class Consumer extends A {void init(BeanManager m){} public String run(){return \"ok\";} }", "Consumer"));
        c.add(new Case("inject-type-variable", "@Dependent public static class A<T> { @Inject T x; }", "A"));
        c.add(new Case("producer-void", "@Dependent public static class A { @Produces void value(){} }", "A"));
        c.add(new Case("producer-wildcard", "@Dependent public static class A { @Produces List<?> value(){return List.of();} }", "A"));
        c.add(new Case("producer-generic-normal", "@Dependent public static class A { @Produces @ApplicationScoped <T> List<T> value(){return List.of();} }", "A"));
        c.add(new Case("producer-inject", "@Dependent public static class A { @Produces @Inject String value(){return \"ok\";} }", "A"));
        c.add(new Case("producer-observer-param", "@Dependent public static class A { @Produces String value(@Observes Integer n){return \"ok\";} }", "A"));
        c.add(new Case("producer-disposes-param", "@Dependent public static class A { @Produces String value(@Disposes Integer n){return \"ok\";} }", "A"));
        c.add(new Case("disposer-no-producer", "@Dependent public static class A { void dispose(@Disposes String x){} }", "A"));
        c.add(new Case("disposer-two-params", "@Dependent public static class A { @Produces String x(){return \"x\";} void dispose(@Disposes String x,@Disposes Integer y){} }", "A"));
        c.add(new Case("disposer-observes-param", "@Dependent public static class A { @Produces String x(){return \"x\";} void dispose(@Disposes String x,@Observes Integer y){} }", "A"));
        c.add(new Case("disposer-inject", "@Dependent public static class A { @Produces String x(){return \"x\";} @Inject void dispose(@Disposes String x){} }", "A"));
        c.add(new Case("observer-two-params", "@Dependent public static class A { void observe(@Observes String x,@Observes Integer y){} }", "A"));
        c.add(new Case("observer-both-kinds", "@Dependent public static class A { void observe(@Observes @ObservesAsync String x){} }", "A"));
        c.add(new Case("observer-inject", "@Dependent public static class A { @Inject void observe(@Observes String x){} }", "A"));
        c.add(new Case("observer-conditional-dependent", "@Dependent public static class A { void observe(@Observes(notifyObserver=Reception.IF_EXISTS) String x){} }", "A"));
        c.add(new Case("normal-final-class", "@ApplicationScoped public static final class A {}", "A"));
        c.add(new Case("normal-final-method", "@ApplicationScoped public static class A { public final void x(){} } @Dependent public static class Consumer { @Inject A a; public String run(){a.x();return \"ok\";} }", "A","Consumer"));
        c.add(new Case("normal-no-noarg", "@ApplicationScoped public static class A { @Inject public A(BeanManager m){} public void x(){} } @Dependent public static class Consumer { @Inject A a; public String run(){a.x();return \"ok\";} }", "A","Consumer"));
        c.add(new Case("normal-public-field", "@ApplicationScoped public static class A { public int x; }", "A"));
        String binding="@InterceptorBinding @Retention(RetentionPolicy.RUNTIME) @java.lang.annotation.Target({ElementType.TYPE,ElementType.METHOD,ElementType.CONSTRUCTOR}) public @interface Bound {} ";
        for(String visibility:List.of("public","protected","private","")) {
            c.add(new Case("interceptor-access-"+(visibility.isEmpty()?"package":visibility),binding+"@Bound @jakarta.interceptor.Interceptor @Priority(100) public static class I { @AroundInvoke "+visibility+" Object around(InvocationContext c)throws Exception{return \"I\"+c.proceed();}} @Dependent @Bound public static class Consumer {public String run(){return \"target\";}}","I","Consumer"));
        }
        for(String declaration:List.of("static Object", "final Object", "String", "void")) {
            String returned=declaration.equals("void")?"":"return null;";
            c.add(new Case("interceptor-invalid-"+declaration.replace(' ','-'),binding+"@Bound @jakarta.interceptor.Interceptor @Priority(100) public static class I {@AroundInvoke public "+declaration+" around(InvocationContext c){"+returned+"}} @Dependent @Bound public static class Consumer {public String run(){return \"target\";}}","I","Consumer"));
        }
        c.add(new Case("interceptor-two-methods",binding+"@Bound @jakarta.interceptor.Interceptor @Priority(100) public static class I { @AroundInvoke Object a(InvocationContext c)throws Exception{return c.proceed();} @AroundInvoke Object b(InvocationContext c)throws Exception{return c.proceed();}} @Dependent @Bound public static class Consumer {public String run(){return \"target\";}}","I","Consumer"));
        c.add(new Case("interceptor-no-binding", "@jakarta.interceptor.Interceptor @Priority(100) public static class I {@AroundInvoke Object a(InvocationContext c)throws Exception{return c.proceed();}}", "I"));
        c.add(new Case("target-around-construct",binding+"@Dependent @Bound public static class Consumer { @AroundConstruct Object a(InvocationContext c)throws Exception{return c.proceed();} public String run(){return \"target\";} }","Consumer"));
        c.add(new Case("target-self-around-invoke", "@Dependent public static class Consumer { @AroundInvoke Object a(InvocationContext c)throws Exception{return \"self\"+c.proceed();} public String run(){return \"target\";} }", "Consumer"));
        c.add(new Case("normal-null-producer", "@Dependent public static class A {@Produces @ApplicationScoped Runnable p(){return null;}} @Dependent public static class Consumer {@Inject Runnable r; public String run(){r.run();return \"ok\";}}", "A","Consumer"));
        c.add(new Case("primitive-null-producer", "@Dependent public static class A {@Produces Integer p(){return null;}} @Dependent public static class Consumer {@Inject int n; public String run(){return \"n=\"+n;}}", "A","Consumer"));
        c.add(new Case("generic-array-lookup", "@Dependent public static class A {@Produces List<String>[] p(){return new List[]{List.of(\"produced\")};}} @Dependent public static class Consumer {@Inject Instance<List<String>[]> arrays; public String run(){return Arrays.deepToString(arrays.get());}}", "A","Consumer"));
        c.add(new Case("raw-wildcard-lookup", "public interface View<T>{} public static class Box<T> implements View<T>{} @Dependent public static class A {@Produces Box p(){return new Box();}} @Dependent public static class Consumer {@Inject Instance<Box<?>> boxes; public String run(){return \"unsatisfied=\"+boxes.isUnsatisfied();}}", "A","Consumer"));
        c.add(new Case("raw-supertype-lookup", "public interface View<T>{} public static class Box<T> implements View<T>{} @Dependent public static class A {@Produces Box p(){return new Box();}} @Dependent public static class Consumer {@Inject Instance<View<String>> views; public String run(){return \"unsatisfied=\"+views.isUnsatisfied();}}", "A","Consumer"));
        for(String visibility:List.of("public","protected","private","")) {
            String callback="@PostConstruct "+visibility+" void init(){LOG.add(\"base\");}";
            c.add(new Case("lifecycle-override-"+(visibility.isEmpty()?"package":visibility),"public static final List<String> LOG=new ArrayList<>(); public static class Base {"+callback+"} @Dependent public static class Consumer extends Base {"+visibility+" void init(){LOG.add(\"sub\");} public String run(){return LOG.toString();}}", "Consumer"));
        }
        for(String visibility:List.of("public","protected","private","")) {
            c.add(new Case("observer-override-"+(visibility.isEmpty()?"package":visibility),"public static int heard; public static class Base {"+visibility+" void observe(@Observes String s){heard++;}} @Dependent public static class Consumer extends Base { @Inject BeanManager bm; "+visibility+" void observe(String s){heard+=100;} public String run(){bm.getEvent().select(String.class).fire(\"event\");return \"heard=\"+heard;}}", "Consumer"));
        }
        interceptionCases(c);
        return c;
    }

    static void interceptionCases(List<Case> cases) {
        for(boolean inherited:List.of(false,true)) for(boolean override:List.of(false,true)) {
            String key="@InterceptorBinding "+(inherited?"@Inherited ":"")+"@Retention(RetentionPolicy.RUNTIME) @java.lang.annotation.Target({ElementType.TYPE,ElementType.METHOD}) public @interface Key {String value(); @Nonbinding String note() default \"\";} ";
            String interceptors="@Key(\"a\") @jakarta.interceptor.Interceptor @Priority(100) public static class A { @AroundInvoke Object around(InvocationContext ctx)throws Exception{return \"A[\"+ctx.proceed()+\"]\";}} @Key(\"b\") @jakarta.interceptor.Interceptor @Priority(200) public static class B { @AroundInvoke Object around(InvocationContext ctx)throws Exception{return \"B[\"+ctx.proceed()+\"]\";}} ";
            cases.add(new Case("binding-inheritance-"+inherited+"-override-"+override,key+interceptors+"@Key(\"a\") public static class Base { public String run(){return \"target\";}} @Dependent public static class Consumer extends Base {"+(override?"@Override public String run(){return \"target\";}":"")+"}","A","B","Consumer"));
            cases.add(new Case("binding-method-override-"+inherited+"-override-"+override,key+interceptors+"@Key(\"a\") public static class Base { @Key(\"b\") public String run(){return \"target\";}} @Dependent public static class Consumer extends Base {"+(override?"@Override @Key(\"a\") public String run(){return \"target\";}":"")+"}","A","B","Consumer"));
        }
        String key="@InterceptorBinding @Retention(RetentionPolicy.RUNTIME) @java.lang.annotation.Target({ElementType.TYPE,ElementType.METHOD,ElementType.CONSTRUCTOR}) public @interface Key {} ";
        for(String returns:List.of("void","Object")) {
            String ending=returns.equals("void")?"return;":"return null;";
            String interceptor="@Key @jakarta.interceptor.Interceptor @Priority(100) public static class I {@AroundConstruct "+returns+" construct(InvocationContext c)throws Exception{LOG.add(\"construct-before:\"+(c.getTarget()==null));LOG.add(\"return-null:\"+(c.proceed()==null));LOG.add(\"construct-after:\"+(c.getTarget()!=null));"+ending+"} @PostConstruct "+returns+" init(InvocationContext c)throws Exception{LOG.add(\"init-before\");LOG.add(\"init-return-null:\"+(c.proceed()==null));"+ending+"} @PreDestroy "+returns+" destroy(InvocationContext c)throws Exception{LOG.add(\"destroy-before\");c.proceed();"+ending+"}} ";
            cases.add(new Case("lifecycle-interceptor-"+returns,"public static final List<String> LOG=new ArrayList<>(); "+key+interceptor+"@Dependent @Key public static class Target {@PostConstruct void init(){LOG.add(\"target-init\");} @PreDestroy void destroy(){LOG.add(\"target-destroy\");} public void ping(){LOG.add(\"ping\");}} @Dependent public static class Consumer {@Inject Instance<Target> targets; public String run(){Target t=targets.get();t.ping();targets.destroy(t);return LOG.toString();}}","I","Target","Consumer"));
        }
        for(boolean failure:List.of(false,true)) {
            cases.add(new Case("proceed-twice-failure-"+failure,"public static int count; "+key+"@Key @jakarta.interceptor.Interceptor @Priority(100) public static class A {@AroundInvoke Object invoke(InvocationContext c)throws Exception{try{c.proceed();}catch(IllegalArgumentException ignored){}return c.proceed();}} @Key @jakarta.interceptor.Interceptor @Priority(200) public static class B {@AroundInvoke Object invoke(InvocationContext c)throws Exception{return \"B\"+c.proceed();}} @Dependent @Key public static class Consumer {public String run(){count++;"+(failure?"if(count==1)throw new IllegalArgumentException();":"")+"return \"target\"+count;}}","A","B","Consumer"));
        }
    }

    @Test void sourceCampaign() throws Exception {
        List<String> rows=new ArrayList<>();
        Path base=Path.of(System.getProperty("fuzz.sources"));Files.createDirectories(base);
        int n=0;
        for(Case c:cases()) {
            String name="Mutation"+(n++),pkg="org.example.cdi.fuzz.generated",fqcn=pkg+"."+name;
            String source="package "+pkg+";\n"+IMPORTS+"public class "+name+" {\n"+c.body+"\n}";
            Path dir=base.resolve(c.id);Files.createDirectories(dir);Path file=dir.resolve(name+".java");Files.writeString(file,source);
            String ref=execute(true,c,file,dir.resolve("weld"),fqcn);
            String actual=execute(false,c,file,dir.resolve("micronaut"),fqcn);
            rows.add(String.join("\t","source",c.id,DifferentialFuzzTest.clean(ref),DifferentialFuzzTest.clean(actual),ref.equals(actual)?"MATCH":"DIFF"));
        }
        Files.write(base.getParent().resolve("source-results.tsv"),rows);
        System.out.println("SOURCE FUZZ comparisons="+rows.size()+" differences="+rows.stream().filter(s->s.endsWith("DIFF")).count());
        assertTrue(rows.size()>40);
        assertTrue(rows.stream().noneMatch(s->s.contains("HARNESS_ERROR")), "compiler/loader failures are not compatibility findings");
        assertTrue(rows.stream().anyMatch(s->s.contains("\tinterceptor-access-public\tOK:Itarget\tOK:Itarget\tMATCH")), "valid interception must work in both compilers/containers");
    }

    static String execute(boolean ref,Case c,Path file,Path out,String fqcn)throws Exception {
        // Each compilation is a deployment. Reusing generated definitions from an earlier corpus is invalid.
        if(Files.exists(out))try(var files=Files.walk(out)) {
            for(Path old:files.sorted(Comparator.reverseOrder()).toList())Files.delete(old);
        }
        Files.createDirectories(out);var diagnostics=new DiagnosticCollector<JavaFileObject>();
        boolean success;
        var compiler=ToolProvider.getSystemJavaCompiler();
        try(var fm=compiler.getStandardFileManager(diagnostics,null,null)) {
            List<String> options=new ArrayList<>(List.of("-classpath",System.getProperty("fuzz.classpath"),"-d",out.toString(),"-s",out.resolve("generated").toString()));
            if(ref)options.add("-proc:none");
            var task=compiler.getTask(null,fm,diagnostics,options,null,fm.getJavaFileObjects(file.toFile()));
            if(!ref)task.setProcessors(List.of(
                new io.micronaut.annotation.processing.MixinVisitorProcessor(),
                new io.micronaut.annotation.processing.PackageElementVisitorProcessor(),
                new io.micronaut.annotation.processing.TypeElementVisitorProcessor(),
                new io.micronaut.annotation.processing.AggregatingTypeElementVisitorProcessor(),
                new io.micronaut.annotation.processing.BeanDefinitionInjectProcessor()));
            success=task.call();
        } catch(Throwable e){Files.writeString(out.resolve("diagnostics.txt"),e.toString());return "HARNESS_ERROR:"+e.getClass().getSimpleName();}
        Files.writeString(out.resolve("diagnostics.txt"),diagnostics.getDiagnostics().toString());
        if(!success)return ref?"HARNESS_ERROR:plain-javac-rejected":"REJECT";
        ClassLoader previous=Thread.currentThread().getContextClassLoader();
        try(var loader=new URLClassLoader(new java.net.URL[]{out.toUri().toURL()},previous)) {
            Thread.currentThread().setContextClassLoader(loader);
            Class<?>[] beans=new Class<?>[c.beans.length];
            for(int i=0;i<beans.length;i++)beans[i]=loader.loadClass(fqcn+"$"+c.beans[i]);
            SeContainer container;
            try {container=ref?new Weld().setClassLoader(loader).disableDiscovery().addBeanClasses(beans).initialize():new MicronautSeContainerInitializer().setClassLoader(loader).disableDiscovery().addBeanClasses(beans).initialize();}
            catch(Throwable e){Files.writeString(out.resolve("deployment.txt"),e.toString());return "REJECT";}
            try(container) {
                for(Class<?> bean:beans)if(bean.getSimpleName().equals("Consumer"))return DifferentialFuzzTest.outcome(()->{
                    try {return bean.getMethod("run").invoke(container.select(bean).get());}
                    catch(Throwable e) {
                        var trace=new java.io.StringWriter();
                        e.printStackTrace(new java.io.PrintWriter(trace));
                        Files.writeString(out.resolve("runtime.txt"),trace.toString());
                        throw e;
                    }
                });
                return "ACCEPT";
            } catch(Throwable e){return "CLOSE_ERROR:"+e.getClass().getSimpleName();}
        }finally{Thread.currentThread().setContextClassLoader(previous);}
    }
}
