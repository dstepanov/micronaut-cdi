package org.example.mp;

import io.micronaut.cdi.se.MicronautSeContainerInitializer;
import jakarta.enterprise.inject.se.SeContainer;
import jakarta.enterprise.inject.spi.Extension;
import org.jboss.weld.environment.se.Weld;
import org.junit.jupiter.api.Test;

import javax.tools.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.lang.reflect.InvocationTargetException;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Actual upstream libraries; plain javac + Weld versus Micronaut processors + public SE bootstrap. */
public class CompatibilityTest {
    record Scenario(String id, String body, String extension, String[] extraBeans, String expected) {
        Scenario(String id, String body, String extension, String expected, String... extraBeans) {
            this(id, body, extension, extraBeans, expected);
        }
    }
    static final String IMPORTS = """
        import jakarta.enterprise.context.*;
        import jakarta.enterprise.inject.*;
        import jakarta.inject.*;
        import org.eclipse.microprofile.config.*;
        import org.eclipse.microprofile.config.inject.*;
        import java.util.*;
        """;
    static final String CONFIG_EXTENSION = "io.smallrye.config.inject.ConfigExtension";
    static final String FT_EXTENSION = "io.smallrye.faulttolerance.FaultToleranceExtension";
    static final String REST_EXTENSION = "org.jboss.resteasy.microprofile.client.RestClientExtension";
    static String configConsumer(String field, String expression) {
        return "@Dependent public static class Consumer {"+field+" public String run(){return \"\"+("+expression+");}}";
    }
    static String configImport() {
        return "@io.micronaut.context.annotation.ClassImport(classes=io.smallrye.config.inject.ConfigProducer.class) public static class Imports {} ";
    }
    static List<Scenario> scenarios() {
        List<Scenario> s=new ArrayList<>();
        s.add(new Scenario("config-programmatic",configConsumer("", "ConfigProvider.getConfig().getValue(\"mp.compat.number\",Integer.class)"),null,"42"));
        s.add(new Scenario("config-cdi-native", configConsumer("@Inject @ConfigProperty(name=\"mp.compat.number\") int number;","number"),CONFIG_EXTENSION,"42"));
        String[][] configCases={
            {"int","int number","mp.compat.number","number","42"},
            {"string","String number","mp.compat.text","number","hello"},
            {"optional","Optional<Integer> number","mp.compat.number","number.orElse(-1)","42"},
            {"provider","Provider<Integer> number","mp.compat.number","number.get()","42"},
            {"instance","Instance<Integer> number","mp.compat.number","number.get()","42"},
            {"optionalint","OptionalInt number","mp.compat.number","number.orElse(-1)","42"},
            {"list","List<Integer> number","mp.compat.list","number","[1, 2, 3]"},
            {"set","Set<Integer> number","mp.compat.list","new TreeSet<>(number)","[1, 2, 3]"},
            {"default","int number","mp.compat.absent\",defaultValue=\"7","number","7"},
            {"config","Config number",null,"number.getValue(\"mp.compat.number\",Integer.class)","42"}
        };
        for(String[] c:configCases) {
            String f="@Inject "+(c[2]==null?"":"@ConfigProperty(name=\""+c[2]+"\") ")+c[1]+";";
            s.add(new Scenario("config-cdi-import-"+c[0],configImport()+configConsumer(f,c[3]),null,c[4],"io.smallrye.config.inject.ConfigProducer"));
        }
        s.add(new Scenario("config-cdi-import-injection-method",configImport()+"""
            @Dependent public static class Consumer {
                int number;
                @Inject void configure(@ConfigProperty(name="mp.compat.number") int value){number=value;}
                public String run(){return ""+number;}
            }
            """,null,"42","io.smallrye.config.inject.ConfigProducer"));
        s.add(new Scenario("config-cdi-native-array", configConsumer("@Inject @ConfigProperty(name=\"mp.compat.list\") Integer[] values;","Arrays.toString(values)"),CONFIG_EXTENSION,"[1, 2, 3]"));
        s.add(new Scenario("fault-tolerance-cdi-native",configImport()+"""
            @ApplicationScoped public static class Consumer {
                int count;
                @org.eclipse.microprofile.faulttolerance.Retry(maxRetries=2,delay=0,jitter=0)
                public String run(){if(++count<3)throw new IllegalStateException("try again");return "attempts="+count;}
            }
            """,FT_EXTENSION,"attempts=3","io.smallrye.config.inject.ConfigProducer"));
        s.add(new Scenario("health-cdi-import","""
            @io.micronaut.context.annotation.ClassImport(classes={io.smallrye.health.SmallRyeHealthReporter.class,io.smallrye.health.AsyncHealthCheckFactory.class})
            public static class Imports {}
            @ApplicationScoped @org.eclipse.microprofile.health.Liveness
            public static class Live implements org.eclipse.microprofile.health.HealthCheck {
                public org.eclipse.microprofile.health.HealthCheckResponse call(){return org.eclipse.microprofile.health.HealthCheckResponse.up("live");}
            }
            @ApplicationScoped @org.eclipse.microprofile.health.Readiness
            public static class Ready implements org.eclipse.microprofile.health.HealthCheck {
                public org.eclipse.microprofile.health.HealthCheckResponse call(){return org.eclipse.microprofile.health.HealthCheckResponse.down("ready");}
            }
            @Dependent public static class Consumer {
                @Inject io.smallrye.health.SmallRyeHealthReporter reporter;
                public String run(){return reporter.getLiveness().isDown()+":"+reporter.getReadiness().isDown()+":"+reporter.getHealth().isDown();}
            }
            """,null,"false:true:true","io.smallrye.health.SmallRyeHealthReporter","io.smallrye.health.AsyncHealthCheckFactory"));
        String health=s.getLast().body;
        s.add(new Scenario("health-cdi-import-all-up",health.replace(".down(\"ready\")",".up(\"ready\")"),null,"false:false:false","io.smallrye.health.SmallRyeHealthReporter","io.smallrye.health.AsyncHealthCheckFactory"));
        s.add(new Scenario("health-cdi-import-payload",health.replace("return reporter.getLiveness().isDown()+\":\"+reporter.getReadiness().isDown()+\":\"+reporter.getHealth().isDown();", "return reporter.getHealth().getPayload().getString(\"status\")+\":\"+reporter.getHealth().getPayload().getJsonArray(\"checks\").size();"),null,"DOWN:2","io.smallrye.health.SmallRyeHealthReporter","io.smallrye.health.AsyncHealthCheckFactory"));
        s.add(new Scenario("health-cdi-import-async",health+"""
            @ApplicationScoped @org.eclipse.microprofile.health.Liveness
            public static class Async implements io.smallrye.health.api.AsyncHealthCheck {
                public io.smallrye.mutiny.Uni<org.eclipse.microprofile.health.HealthCheckResponse> call(){return io.smallrye.mutiny.Uni.createFrom().item(org.eclipse.microprofile.health.HealthCheckResponse.down("async"));}
            }
            """,null,"true:true:true","io.smallrye.health.SmallRyeHealthReporter","io.smallrye.health.AsyncHealthCheckFactory"));
        s.add(new Scenario("openapi-factory",configConsumer("", "org.eclipse.microprofile.openapi.OASFactory.createOpenAPI().info(org.eclipse.microprofile.openapi.OASFactory.createInfo().title(\"compat\").version(\"1\")).getInfo().getTitle()"),null,"compat"));
        s.add(new Scenario("openapi-jaxrs-scan","""
            @jakarta.ws.rs.Path("/hello") public static class Resource {
                @jakarta.ws.rs.GET @org.eclipse.microprofile.openapi.annotations.Operation(operationId="hello")
                public String hello(){return "hello";}
            }
            @Dependent public static class Consumer {
                public String run() throws Exception {
                    var api=io.smallrye.openapi.api.SmallRyeOpenAPI.builder()
                        .withConfig(ConfigProvider.getConfig())
                        .withIndex(org.jboss.jandex.Index.of(Resource.class))
                        .enableStandardStaticFiles(false).enableStandardFilter(false).build();
                    return api.model().getPaths().getPathItem("/hello").getGET().getOperationId();
                }
            }
            """,null,"hello"));
        String rest="""
            @org.eclipse.microprofile.rest.client.inject.RegisterRestClient
            @jakarta.ws.rs.Path("/ping")
            public interface Remote extends AutoCloseable {
                @jakarta.ws.rs.GET String ping();
                void close();
            }
            """;
        s.add(new Scenario("rest-client-programmatic",rest+"""
            @Dependent public static class Consumer {
                public String run() throws Exception {
                    try(Remote remote=org.eclipse.microprofile.rest.client.RestClientBuilder.newBuilder().baseUri(java.net.URI.create(System.getProperty("mp.compat.base"))).build(Remote.class)){return remote.ping();}
                }
            }
            """,null,"pong"));
        s.add(new Scenario("rest-client-cdi-native",rest+"""
            @Dependent public static class Consumer {
                @Inject @org.eclipse.microprofile.rest.client.inject.RestClient Remote remote;
                public String run(){return remote.ping();}
            }
            """,REST_EXTENSION,"pong"));
        s.add(new Scenario("rest-client-explicit-producer",rest+"""
            @Dependent public static class Producer {
                @Produces @Dependent @org.eclipse.microprofile.rest.client.inject.RestClient
                Remote remote(){return org.eclipse.microprofile.rest.client.RestClientBuilder.newBuilder().baseUri(java.net.URI.create(System.getProperty("mp.compat.base"))).build(Remote.class);}
                void dispose(@Disposes @org.eclipse.microprofile.rest.client.inject.RestClient Remote remote){remote.close();}
            }
            @Dependent public static class Consumer {
                @Inject @org.eclipse.microprofile.rest.client.inject.RestClient Remote remote;
                public String run(){return remote.ping();}
            }
            """,null,"pong"));
        s.add(new Scenario("telemetry-cdi-native",configImport()+"""
            @Dependent public static class Producer {
                @Produces io.helidon.config.Config helidonConfig(){return io.helidon.config.Config.empty();}
            }
            @ApplicationScoped public static class Consumer {
                @io.opentelemetry.instrumentation.annotations.WithSpan("compatibility")
                public String run(){return "span="+io.opentelemetry.api.trace.Span.current().getSpanContext().isValid();}
            }
            ""","io.helidon.microprofile.telemetry.TelemetryCdiExtension","span=true","io.smallrye.config.inject.ConfigProducer"));
        // Valid token and claim injection only: these are functional checks, not security fuzzing.
        String jwt="""
            public static org.eclipse.microprofile.jwt.JsonWebToken token() {
                try {
                    var keys=java.security.KeyPairGenerator.getInstance("RSA");keys.initialize(2048);var pair=keys.generateKeyPair();
                    String text=io.smallrye.jwt.build.Jwt.issuer("compat").upn("alice").groups(Set.of("users")).sign(pair.getPrivate());
                    return new io.smallrye.jwt.auth.principal.DefaultJWTParser(new io.smallrye.jwt.auth.principal.JWTAuthContextInfo(pair.getPublic(),"compat")).parse(text);
                }catch(Exception e){throw new IllegalStateException(e);}
            }
            """;
        s.add(new Scenario("jwt-programmatic",jwt+configConsumer("", "token().getName()+\":\"+token().getGroups().contains(\"users\")"),null,"alice:true"));
        String claims=jwt+"""
            @io.micronaut.context.annotation.ClassImport(classes={io.smallrye.jwt.auth.cdi.RawClaimTypeProducer.class,io.smallrye.jwt.auth.cdi.CommonJwtProducer.class,io.smallrye.jwt.auth.cdi.OptionalClaimTypeProducer.class,io.smallrye.jwt.auth.cdi.ClaimValueProducer.class})
            public static class Imports {}
            @Dependent public static class Producer {
                @Produces @RequestScoped org.eclipse.microprofile.jwt.JsonWebToken current(){return token();}
            }
            """;
        s.add(new Scenario("jwt-request-producer",jwt+"""
            @Dependent public static class Producer {
                @Produces @RequestScoped org.eclipse.microprofile.jwt.JsonWebToken current(){return token();}
            }
            @RequestScoped public static class Reader {
                @Inject org.eclipse.microprofile.jwt.JsonWebToken current;
                public String value(){return current.getName();}
            }
            @Dependent public static class Consumer {
                @Inject jakarta.enterprise.context.control.RequestContextController controller;
                @Inject Instance<Reader> readers;
                public String run(){controller.activate();try{return readers.get().value();}finally{controller.deactivate();}}
            }
            """,null,"alice"));
        s.add(new Scenario("jwt-request-producer-with-type",s.getLast().body,null,"alice","org.eclipse.microprofile.jwt.JsonWebToken"));
        for(String[] c:List.of(
                new String[]{"string","String value","upn","value","alice"},
                new String[]{"groups","Set<String> value","groups","new TreeSet<>(value)","[users]"},
                new String[]{"optional","Optional<String> value","upn","value.orElse(\"absent\")","alice"},
                new String[]{"claimvalue","org.eclipse.microprofile.jwt.ClaimValue<String> value","upn","value.getValue()","alice"})) {
            s.add(new Scenario("jwt-claim-import-"+c[0],claims+"@RequestScoped public static class ClaimsBean {@Inject @org.eclipse.microprofile.jwt.Claim(\""+c[2]+"\") "+c[1]+";public String value(){return \"\"+("+c[3]+");}}"+"""
                @Dependent public static class Consumer {
                    @Inject jakarta.enterprise.context.control.RequestContextController controller;
                    @Inject Instance<ClaimsBean> claims;
                    public String run(){controller.activate();try{return claims.get().value();}finally{controller.deactivate();}}
                }
                """,null,c[4],"io.smallrye.jwt.auth.cdi.RawClaimTypeProducer","io.smallrye.jwt.auth.cdi.CommonJwtProducer","io.smallrye.jwt.auth.cdi.OptionalClaimTypeProducer","io.smallrye.jwt.auth.cdi.ClaimValueProducer","org.eclipse.microprofile.jwt.JsonWebToken"));
        }
        s.add(new Scenario("provider-injection-metadata", """
            @Qualifier @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
            @java.lang.annotation.Target({java.lang.annotation.ElementType.FIELD,java.lang.annotation.ElementType.PARAMETER,java.lang.annotation.ElementType.METHOD})
            public @interface Key { @jakarta.enterprise.util.Nonbinding String value() default ""; }
            public static class Payload { final jakarta.enterprise.inject.spi.InjectionPoint point;
                Payload(jakarta.enterprise.inject.spi.InjectionPoint point) { this.point=point; } }
            @Dependent public static class Producer {
                @Produces @Key Payload payload(jakarta.enterprise.inject.spi.InjectionPoint point) { return new Payload(point); }
            }
            @Dependent public static class Consumer {
                @Inject @Key("field") Provider<Payload> field;
                final Provider<Payload> constructor;
                @Inject public Consumer(@Key("ctor") Provider<Payload> constructor) { this.constructor=constructor; }
                static String describe(Payload payload) {
                    var point=payload.point;
                    String member=point.getMember() instanceof java.lang.reflect.Constructor ? "ctor" : point.getMember().getName();
                    String key=point.getQualifiers().stream().filter(Key.class::isInstance).map(Key.class::cast).findFirst().orElseThrow().value();
                    return ((Class<?>) point.getType()).getSimpleName()+":"+member+":"+key+":"+(point.getBean().getBeanClass()==Consumer.class);
                }
                public String run(){return describe(field.get())+"|"+describe(constructor.get());}
            }
            """, null, "Payload:field:field:true|Payload:ctor:ctor:true"));
        for (String[] probe : List.of(
            new String[]{"optional", "Optional<Integer>", "mp.compat.number", "number.get().orElse(-1)", "42"},
            new String[]{"list", "List<Integer>", "mp.compat.list", "number.get()", "[1, 2, 3]"},
            new String[]{"set", "Set<Integer>", "mp.compat.list", "new TreeSet<>(number.get())", "[1, 2, 3]"})) {
            s.add(new Scenario("config-cdi-import-" + probe[0] + "-via-instance", configImport()
                + "@Dependent public static class Consumer {@Inject @ConfigProperty(name=\"" + probe[2]
                + "\") Instance<" + probe[1] + "> number; public String run(){return \"\"+(" + probe[3] + ");}}",
                null, probe[4], "io.smallrye.config.inject.ConfigProducer"));
        }
        // Full-type injection must preserve qualifiers and conversion for every managed injection route.
        for (String[] probe : List.of(
            new String[]{"optional", "Optional<Integer>", "mp.compat.number", "number.orElse(-1)", "42"},
            new String[]{"list", "List<Integer>", "mp.compat.list", "number", "[1, 2, 3]"},
            new String[]{"set", "Set<Integer>", "mp.compat.list", "new TreeSet<>(number)", "[1, 2, 3]"})) {
            String parameter = "@ConfigProperty(name=\"" + probe[2] + "\") " + probe[1] + " value";
            for (boolean constructor : List.of(true, false)) {
                String injection = constructor
                    ? "@Inject public Consumer(" + parameter + "){number=value;}"
                    : "@Inject void initialize(" + parameter + "){number=value;}";
                s.add(new Scenario("config-cdi-import-" + probe[0] + (constructor ? "-constructor" : "-initializer"),
                    configImport() + "@Dependent public static class Consumer {" + probe[1] + " number;"
                        + injection + " public String run(){return \"\"+(" + probe[3] + ");}}",
                    null, probe[4], "io.smallrye.config.inject.ConfigProducer"));
            }
        }
        s.add(new Scenario("config-cdi-import-optional-absent", configImport()
            + configConsumer("@Inject @ConfigProperty(name=\"mp.compat.absent\") Optional<Integer> number;", "number.orElse(-1)"),
            null, "-1", "io.smallrye.config.inject.ConfigProducer"));
        String converted = "public static class Converted {final String text;public Converted(String text){this.text=text;}"
            + "public String toString(){return \"converted:\"+text;}}";
        for (String[] probe : List.of(
            new String[]{"optional", "Optional<Converted>", "mp.compat.number", "number.orElseThrow()", "converted:42"},
            new String[]{"list", "List<Converted>", "mp.compat.list", "number", "[converted:1, converted:2, converted:3]"})) {
            s.add(new Scenario("config-cdi-import-" + probe[0] + "-custom-converter", configImport() + converted
                + configConsumer("@Inject @ConfigProperty(name=\"" + probe[2] + "\") " + probe[1] + " number;", probe[3]),
                null, probe[4], "io.smallrye.config.inject.ConfigProducer"));
        }
        Scenario groups = s.stream().filter(probe -> probe.id.equals("jwt-claim-import-groups")).findFirst().orElseThrow();
        s.add(new Scenario("jwt-claim-import-groups-via-instance",
            groups.body.replace("Set<String> value", "Instance<Set<String>> value")
                .replace("new TreeSet<>(value)", "new TreeSet<>(value.get())"),
            null, groups.extraBeans, "[users]"));
        return s;
    }

    @Test void integrations() throws Exception {
        Path base=Path.of(System.getProperty("mp.output"));Files.createDirectories(base);
        String referenceOrigin=Weld.class.getProtectionDomain().getCodeSource().getLocation().toString();
        assertTrue(referenceOrigin.endsWith("weld-se-core-6.0.4.Final.jar"),"Reference container was replaced: "+referenceOrigin);
        Files.writeString(base.resolve("origins.tsv"),"Weld\t"+referenceOrigin+"\nWeldBootstrap\t"+org.jboss.weld.bootstrap.WeldBootstrap.class.getProtectionDomain().getCodeSource().getLocation()+"\n");
        var server=com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/ping",exchange->{byte[] b="pong".getBytes();exchange.getResponseHeaders().set("Content-Type","text/plain");exchange.sendResponseHeaders(200,b.length);try(var stream=exchange.getResponseBody()){stream.write(b);}});
        server.start();
        System.setProperty("mp.compat.base","http://127.0.0.1:"+server.getAddress().getPort());
        System.setProperty("mp.compat.number","42");System.setProperty("mp.compat.text","hello");System.setProperty("mp.compat.list","1,2,3");
        // FT runs without an optional telemetry backend; no exporters, agents or network services.
        System.setProperty("smallrye.faulttolerance.mp-compatibility","true");
        System.setProperty("smallrye.faulttolerance.metrics.enabled","false");
        System.setProperty("otel.sdk.disabled","false");
        System.setProperty("otel.traces.exporter","none");
        System.setProperty("otel.metrics.exporter","none");
        System.setProperty("otel.logs.exporter","none");
        // Multiple libraries bring Config providers. Select the one under test explicitly.
        org.eclipse.microprofile.config.spi.ConfigProviderResolver.setInstance(new io.smallrye.config.SmallRyeConfigProviderResolver());
        List<String> rows=new ArrayList<>();
        try {
            int i=0;
            for(Scenario s:scenarios()) {
                String name="Integration"+(i++),fqcn="org.example.mp.generated."+name;
                Path dir=base.resolve(s.id);Files.createDirectories(dir);Path file=dir.resolve(name+".java");
                Files.writeString(file,"package org.example.mp.generated;\n"+IMPORTS+"public class "+name+" {\n"+s.body+"\n}");
                String ref=execute(true,s,file,dir.resolve("weld"),fqcn);
                String mn=execute(false,s,file,dir.resolve("micronaut"),fqcn);
                rows.add(String.join("\t",s.id,clean(ref),clean(mn),clean(s.expected),ref.equals(mn)?"MATCH":"DIFF"));
                System.out.println(rows.getLast());
            }
        } finally {server.stop(0);}
        Files.write(base.resolve("results.tsv"),rows);
        assertFalse(rows.stream().anyMatch(r->r.contains("HARNESS_ERROR")),"Do not mistake a broken harness for incompatibility");
        assertTrue(rows.stream().allMatch(r->r.split("\t")[1].equals("OK:"+r.split("\t")[3])),"Every reference fixture must work on Weld: "+rows);
        for(String id:List.of("config-programmatic","health-cdi-import","openapi-jaxrs-scan","rest-client-programmatic","rest-client-explicit-producer", "config-cdi-import-optional", "config-cdi-import-list", "config-cdi-import-set",
            "jwt-claim-import-groups", "jwt-claim-import-optional", "config-cdi-import-optional-constructor",
            "config-cdi-import-optional-initializer", "config-cdi-import-list-constructor", "config-cdi-import-list-initializer",
            "config-cdi-import-set-constructor", "config-cdi-import-set-initializer", "config-cdi-import-optional-absent",
            "config-cdi-import-optional-custom-converter", "config-cdi-import-list-custom-converter")) {
            assertTrue(rows.stream().anyMatch(r->r.startsWith(id+"\tOK:")&&r.endsWith("\tMATCH")),"Positive control failed: "+id);
        }
    }
    static String clean(String s){return s.replace('\n',' ').replace('\r',' ').replace('\t',' ');}
    static String execute(boolean ref,Scenario s,Path file,Path out,String fqcn)throws Exception {
        if(Files.exists(out))try(var paths=Files.walk(out)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}
        Files.createDirectories(out);
        DiagnosticCollector<JavaFileObject> diagnostics=new DiagnosticCollector<>();
        var compiler=ToolProvider.getSystemJavaCompiler();boolean compiled;
        try(var fm=compiler.getStandardFileManager(diagnostics,null,null)) {
            List<String> options=new ArrayList<>(List.of("-classpath",System.getProperty("mp.classpath"),"-d",out.toString(),"-s",out.resolve("generated").toString()));
            if(ref)options.add("-proc:none");
            var task=compiler.getTask(null,fm,diagnostics,options,null,fm.getJavaFileObjects(file.toFile()));
            if(!ref)task.setProcessors(List.of(new io.micronaut.annotation.processing.MixinVisitorProcessor(),new io.micronaut.annotation.processing.PackageElementVisitorProcessor(),new io.micronaut.annotation.processing.TypeElementVisitorProcessor(),new io.micronaut.annotation.processing.AggregatingTypeElementVisitorProcessor(),new io.micronaut.annotation.processing.BeanDefinitionInjectProcessor()));
            compiled=task.call();
        }catch(Throwable e){writeError(out,"compiler",e);return "HARNESS_ERROR:"+e;}
        Files.writeString(out.resolve("diagnostics.txt"),diagnostics.getDiagnostics().toString());
        if(!compiled)return "COMPILE_REJECT";
        ClassLoader previous=Thread.currentThread().getContextClassLoader();
        // Extension services are isolated per scenario. The selected upstream extension is installed explicitly.
        try(var loader=new URLClassLoader(new URL[]{out.toUri().toURL()},previous) {
            @Override public Enumeration<URL> getResources(String name)throws IOException {
                if(name.equals("META-INF/services/jakarta.enterprise.inject.spi.Extension"))return Collections.emptyEnumeration();
                return super.getResources(name);
            }
        }) {
            Thread.currentThread().setContextClassLoader(loader);
            // ConfigProducer alone expects an already registered SmallRye configuration.
            io.smallrye.config.Config.getOrCreate(loader);
            System.setProperty(fqcn+"$Remote/mp-rest/url",System.getProperty("mp.compat.base"));
            List<Class<?>> beans=new ArrayList<>();Class<?> outer=loader.loadClass(fqcn);
            for(Class<?> b:outer.getDeclaredClasses())if(!b.getSimpleName().equals("Imports"))beans.add(b);
            for(String n:s.extraBeans)beans.add(loader.loadClass(n));
            Extension extension=s.extension==null?null:s.extension.equals(FT_EXTENSION)
                ?new io.smallrye.faulttolerance.FaultToleranceExtension(io.smallrye.faulttolerance.metrics.MetricsIntegration.NOOP)
                :(Extension)loader.loadClass(s.extension).getConstructor().newInstance();
            SeContainer container;
            try {
                if(ref) {
                    Weld w=new Weld().setClassLoader(loader).disableDiscovery().addBeanClasses(beans.toArray(Class<?>[]::new));
                    if(extension!=null)w.addExtensions(extension);container=w.initialize();
                } else {
                    var m=new MicronautSeContainerInitializer().setClassLoader(loader).disableDiscovery().addBeanClasses(beans.toArray(Class<?>[]::new));
                    if(extension!=null)m.addExtensions(extension);container=m.initialize();
                }
            } catch(Throwable e){writeError(out,"deployment",e);return failure("DEPLOY_REJECT",e);}
            try(container) {
                Class<?> consumer=loader.loadClass(fqcn+"$Consumer");
                try{return "OK:"+consumer.getMethod("run").invoke(container.select(consumer).get());}
                catch(Throwable e){writeError(out,"runtime",e);return failure("RUNTIME_ERROR",e);}
            }
        } finally {Thread.currentThread().setContextClassLoader(previous);}
    }
    static void writeError(Path dir,String phase,Throwable e)throws IOException {
        StringWriter sw=new StringWriter();e.printStackTrace(new PrintWriter(sw));Files.writeString(dir.resolve(phase+".txt"),sw.toString());
    }
    static String failure(String phase,Throwable e) {
        while(e.getCause()!=null&&e.getCause()!=e)e=e.getCause();
        return phase+":"+e.getClass().getSimpleName()+":"+clean(String.valueOf(e.getMessage()));
    }
}
