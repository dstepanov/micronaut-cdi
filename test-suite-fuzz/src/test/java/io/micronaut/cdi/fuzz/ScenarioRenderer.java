/*
 * Copyright 2017-2026 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.cdi.fuzz;

import io.micronaut.cdi.fuzz.Scenario.BeanDef;
import io.micronaut.cdi.fuzz.Scenario.DecoratorDef;
import io.micronaut.cdi.fuzz.Scenario.InjectionPointDef;
import io.micronaut.cdi.fuzz.Scenario.InterceptorDef;
import io.micronaut.cdi.fuzz.Scenario.IpKind;
import io.micronaut.cdi.fuzz.Scenario.ObserverDef;
import io.micronaut.cdi.fuzz.Scenario.Probe;
import io.micronaut.cdi.fuzz.Scenario.ProducerDef;
import io.micronaut.cdi.fuzz.Scenario.QualUse;
import io.micronaut.cdi.fuzz.Scenario.Scope;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Renders a scenario as Java sources: the shared vocabulary, a class per bean, interceptor and decorator, and the
 * driver that runs the probes against a started {@code SeContainer} and returns what was logged.
 *
 * <p>The sources name nothing of either implementation: the same sources are compiled for both, and everything
 * they observe is written to the {@code Log} class of the scenario's own package, which each implementation loads a
 * copy of.</p>
 */
final class ScenarioRenderer {

    static final String DEFAULT_OBSERVER_PRIORITY = "2500";

    private static final String IMPORTS = """
        import jakarta.annotation.PostConstruct;
        import jakarta.annotation.PreDestroy;
        import jakarta.annotation.Priority;
        import jakarta.decorator.*;
        import jakarta.enterprise.context.*;
        import jakarta.enterprise.event.*;
        import jakarta.enterprise.inject.*;
        import jakarta.enterprise.util.*;
        import jakarta.inject.*;
        import jakarta.interceptor.*;
        import java.lang.annotation.*;
        import java.util.*;
        """;

    private ScenarioRenderer() {
    }

    /**
     * The rendered scenario.
     *
     * @param packageName The package every class is in
     * @param sources     The source of each class, by fully qualified name
     * @param beanClasses The classes that are beans, interceptors or decorators, by fully qualified name: what an
     *                    implementation without discovery is handed
     */
    record Rendered(String packageName, Map<String, String> sources, List<String> beanClasses) {
    }

    static Rendered render(Scenario scenario, String packageName) {
        Map<String, String> sources = new LinkedHashMap<>();
        List<String> beanClasses = new ArrayList<>();
        vocabulary(packageName, sources);
        for (BeanDef bean : scenario.beans()) {
            sources.put(packageName + "." + bean.name(), file(packageName, bean(scenario, bean)));
            beanClasses.add(packageName + "." + bean.name());
        }
        for (InterceptorDef interceptor : scenario.interceptors()) {
            sources.put(packageName + "." + interceptor.name(), file(packageName, interceptor(interceptor)));
            beanClasses.add(packageName + "." + interceptor.name());
        }
        for (DecoratorDef decorator : scenario.decorators()) {
            sources.put(packageName + "." + decorator.name(), file(packageName, decorator(decorator)));
            beanClasses.add(packageName + "." + decorator.name());
        }
        sources.put(packageName + ".Driver", file(packageName, driver(scenario)));
        return new Rendered(packageName, sources, beanClasses);
    }

    private static String file(String packageName, String body) {
        return "package " + packageName + ";\n\n" + IMPORTS + "\n" + body;
    }

    private static void vocabulary(String pkg, Map<String, String> sources) {
        put(sources, pkg, "Svc", "public interface Svc {\n    String id();\n}\n");
        put(sources, pkg, "S1", "public interface S1 extends Svc {\n}\n");
        put(sources, pkg, "S2", "public interface S2 extends S1 {\n}\n");
        put(sources, pkg, "S3", "public interface S3 extends Svc {\n}\n");
        put(sources, pkg, "Gen", "public interface Gen<T> extends Svc {\n}\n");
        put(sources, pkg, "Probe", "public interface Probe {\n    String describe();\n}\n");
        // the classes a producer returns, which are not beans: no bean defining annotation, no constructor a
        // container could call
        for (String type : Scenario.SERVICE_TYPES) {
            String impl = implOf(type);
            put(sources, pkg, impl, "public class " + impl + " implements " + type + " {\n"
                + "    private final String id;\n"
                + "    public " + impl + "(String id) {\n        this.id = id;\n    }\n"
                + "    @Override\n    public String id() {\n        return id;\n    }\n}\n");
        }
        String qualifierTargets = "@Retention(RetentionPolicy.RUNTIME)\n"
            + "@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD, ElementType.PARAMETER})\n";
        put(sources, pkg, "Q1", "@Qualifier\n" + qualifierTargets + "public @interface Q1 {\n}\n");
        put(sources, pkg, "Q2", "@Qualifier\n" + qualifierTargets + "public @interface Q2 {\n    String value();\n}\n");
        put(sources, pkg, "Q3", "@Qualifier\n" + qualifierTargets
            + "public @interface Q3 {\n    String value();\n    @Nonbinding String note() default \"\";\n}\n");
        put(sources, pkg, "Q1Literal", "public final class Q1Literal extends AnnotationLiteral<Q1> implements Q1 {\n}\n");
        put(sources, pkg, "Q2Literal", "public final class Q2Literal extends AnnotationLiteral<Q2> implements Q2 {\n"
            + "    private final String value;\n"
            + "    public Q2Literal(String value) {\n        this.value = value;\n    }\n"
            + "    @Override\n    public String value() {\n        return value;\n    }\n}\n");
        put(sources, pkg, "Q3Literal", "public final class Q3Literal extends AnnotationLiteral<Q3> implements Q3 {\n"
            + "    private final String value;\n    private final String note;\n"
            + "    public Q3Literal(String value, String note) {\n        this.value = value;\n        this.note = note;\n    }\n"
            + "    @Override\n    public String value() {\n        return value;\n    }\n"
            + "    @Override\n    public String note() {\n        return note;\n    }\n}\n");
        String bindingTargets = "@Retention(RetentionPolicy.RUNTIME)\n@Target({ElementType.TYPE, ElementType.METHOD})\n";
        put(sources, pkg, "Bind1", "@InterceptorBinding\n@Inherited\n" + bindingTargets + "public @interface Bind1 {\n}\n");
        put(sources, pkg, "Bind2", "@InterceptorBinding\n" + bindingTargets + "public @interface Bind2 {\n}\n");
        put(sources, pkg, "Ev", "public class Ev {\n}\n");
        put(sources, pkg, "EvA", "public class EvA extends Ev {\n}\n");
        put(sources, pkg, "EvB", "public class EvB extends Ev {\n}\n");
        put(sources, pkg, "GEv", "public class GEv<T> extends Ev {\n}\n");
        put(sources, pkg, "Log", LOG);
    }

    private static void put(Map<String, String> sources, String pkg, String name, String body) {
        sources.put(pkg + "." + name, file(pkg, body));
    }

    private static final String LOG = """
        /**
         * What the scenario observed, in the order it happened. A line "{" opens a stretch whose order is not
         * specified and "}" closes it; "[" opens the notification of an event, whose observers log their priority.
         */
        public final class Log {
            private static final List<String> LINES = new ArrayList<>();

            private Log() {
            }

            public static synchronized void add(String line) {
                LINES.add(line);
            }

            public static synchronized List<String> drain() {
                List<String> copy = new ArrayList<>(LINES);
                LINES.clear();
                return copy;
            }

            /**
             * The first class of the throwable's hierarchy that belongs to the platform or to a Jakarta
             * specification, so that the exceptions of the two implementations are compared as the API names them.
             */
            public static String kind(Throwable t) {
                Class<?> type = t.getClass();
                while (!(type.getName().startsWith("java.") || type.getName().startsWith("jakarta."))) {
                    type = type.getSuperclass();
                }
                return type.getName();
            }

            /**
             * The class of the scenario an instance is of, past the proxies and subclasses an implementation
             * generates.
             */
            public static String self(Object o) {
                Class<?> type = o.getClass();
                while (type.getSimpleName().contains("$") || type.getSimpleName().isEmpty()) {
                    type = type.getSuperclass();
                }
                return type.getSimpleName();
            }

            public static String ev(Object event) {
                Class<?> type = event.getClass();
                if (type.getPackageName().equals(Log.class.getPackageName())
                    || type.getName().startsWith("jakarta.enterprise.event.")) {
                    return type.getSimpleName();
                }
                return "other";
            }

            public static String call(java.util.function.Supplier<String> call) {
                try {
                    return call.get();
                } catch (Throwable t) {
                    return "!" + kind(t);
                }
            }

            public static String instance(Instance<? extends Svc> instance) {
                return "unsatisfied=" + call(() -> String.valueOf(instance.isUnsatisfied()))
                    + " ambiguous=" + call(() -> String.valueOf(instance.isAmbiguous()))
                    + " get=" + call(() -> instance.get().id())
                    + " all=" + unorderedCall(() -> String.valueOf(instance.stream().map(Svc::id).sorted().toList()));
            }

            // what a call logs as it runs - the callbacks of the instances a stream creates, in the order it visits
            // them, which is not specified - is an unordered stretch
            public static String unorderedCall(java.util.function.Supplier<String> call) {
                add("{");
                try {
                    return call(call);
                } finally {
                    add("}");
                }
            }

            public static void unordered(Runnable run) {
                add("{");
                try {
                    run.run();
                } catch (Throwable t) {
                    add("!" + kind(t));
                } finally {
                    add("}");
                }
            }

            public static void notification(Runnable run) {
                add("[");
                try {
                    run.run();
                } catch (Throwable t) {
                    add("!" + kind(t));
                } finally {
                    add("]");
                }
            }
        }
        """;

    static String implOf(String type) {
        return switch (type) {
            case "Gen<String>" -> "GenStringImpl";
            case "Gen<Integer>" -> "GenIntegerImpl";
            default -> type + "Impl";
        };
    }

    /**
     * A type as a class literal, its erasure where it is parameterized.
     */
    private static String classLiteral(String type) {
        int angle = type.indexOf('<');
        return (angle < 0 ? type : type.substring(0, angle)) + ".class";
    }

    /**
     * A type as the first argument of a {@code select}: a class literal, or a {@code TypeLiteral} where it is
     * parameterized.
     */
    private static String selectType(String type) {
        return type.contains("<") ? "new TypeLiteral<" + type + ">() { }" : type + ".class";
    }

    private static String annotations(List<QualUse> qualifiers) {
        return qualifiers.stream().map(q -> q.annotation() + " ").collect(Collectors.joining());
    }

    private static String literals(List<QualUse> qualifiers) {
        return qualifiers.stream().map(q -> ", " + q.literal()).collect(Collectors.joining());
    }

    private static String typed(List<String> typed) {
        if (typed == null) {
            return "";
        }
        return "@Typed({" + typed.stream().map(ScenarioRenderer::classLiteral).collect(Collectors.joining(", "))
            + "})\n";
    }

    private static String bean(Scenario scenario, BeanDef bean) {
        StringBuilder out = new StringBuilder();
        out.append('@').append(bean.scope().annotation).append('\n');
        bean.qualifiers().forEach(q -> out.append(q.annotation()).append('\n'));
        out.append(typed(bean.typed()));
        if (bean.alternative()) {
            out.append("@Alternative\n");
        }
        if (bean.priority() != null) {
            out.append("@Priority(").append(bean.priority()).append(")\n");
        }
        bean.bindings().forEach(b -> out.append('@').append(b).append('\n'));
        out.append("public class ").append(bean.name());
        if (bean.generic()) {
            out.append("<T>");
        }
        if (bean.superBean() != null) {
            out.append(" extends ").append(bean.superBean());
        }
        List<String> interfaces = new ArrayList<>(bean.interfaces());
        if (bean.generic()) {
            interfaces.add("Gen<T>");
        }
        boolean probe = !bean.injectionPoints().isEmpty();
        if (probe) {
            interfaces.add("Probe");
        }
        if (!interfaces.isEmpty()) {
            out.append(" implements ").append(String.join(", ", interfaces));
        }
        out.append(" {\n\n");
        String n = bean.name();

        List<InjectionPointDef> constructorPoints = new ArrayList<>();
        List<InjectionPointDef> initializerPoints = new ArrayList<>();
        for (InjectionPointDef ip : bean.injectionPoints()) {
            String field = n + "_" + ip.name();
            switch (ip.kind()) {
                case FIELD -> out.append("    @Inject ").append(annotations(ip.qualifiers())).append(ip.type())
                    .append(' ').append(field).append(";\n");
                case INSTANCE -> out.append("    @Inject ").append(annotations(ip.qualifiers())).append("Instance<")
                    .append(ip.type()).append("> ").append(field).append(";\n");
                case PROVIDER -> out.append("    @Inject ").append(annotations(ip.qualifiers())).append("Provider<")
                    .append(ip.type()).append("> ").append(field).append(";\n");
                case CONSTRUCTOR -> {
                    constructorPoints.add(ip);
                    out.append("    ").append(ip.type()).append(' ').append(field).append(";\n");
                }
                case INITIALIZER -> {
                    initializerPoints.add(ip);
                    out.append("    ").append(ip.type()).append(' ').append(field).append(";\n");
                }
                default -> throw new IllegalStateException(ip.kind().name());
            }
        }
        // the constructor a proxy or a subclass calls, which every class has
        out.append("\n    public ").append(n).append("() {\n    }\n");
        if (!constructorPoints.isEmpty()) {
            out.append("\n    @Inject\n    public ").append(n).append('(')
                .append(constructorPoints.stream().map(ip -> annotations(ip.qualifiers()) + ip.type() + " " + ip.name())
                    .collect(Collectors.joining(", ")))
                .append(") {\n");
            constructorPoints.forEach(ip -> out.append("        this.").append(n).append('_').append(ip.name())
                .append(" = ").append(ip.name()).append(";\n"));
            out.append("    }\n");
        }
        if (!initializerPoints.isEmpty()) {
            out.append("\n    @Inject\n    void init").append(n).append('(')
                .append(initializerPoints.stream().map(ip -> annotations(ip.qualifiers()) + ip.type() + " " + ip.name())
                    .collect(Collectors.joining(", ")))
                .append(") {\n");
            initializerPoints.forEach(ip -> out.append("        this.").append(n).append('_').append(ip.name())
                .append(" = ").append(ip.name()).append(";\n"));
            out.append("    }\n");
        }

        out.append('\n');
        bean.methodBindings().forEach(b -> out.append("    @").append(b).append('\n'));
        out.append("    public String id() {\n        return \"").append(n).append("\";\n    }\n");

        if (probe) {
            out.append("\n    @Override\n    public String describe() {\n        StringBuilder out = new StringBuilder(\"")
                .append(n).append("\");\n");
            for (InjectionPointDef ip : bean.injectionPoints()) {
                String field = n + "_" + ip.name();
                String value = switch (ip.kind()) {
                    case FIELD, CONSTRUCTOR, INITIALIZER -> "Log.call(() -> " + field + ".id())";
                    case INSTANCE -> "Log.call(() -> Log.instance(" + field + "))";
                    case PROVIDER -> "Log.call(() -> " + field + ".get().id())";
                };
                out.append("        out.append(\" ").append(ip.name()).append("=\").append(").append(value)
                    .append(");\n");
            }
            out.append("        return out.toString();\n    }\n");
        }

        if (bean.postConstruct()) {
            out.append("\n    @PostConstruct\n    void postConstruct").append(n).append("() {\n        Log.add(\"")
                .append(n).append(".postConstruct@\" + Log.self(this));\n    }\n");
        }
        if (bean.preDestroy()) {
            out.append("\n    @PreDestroy\n    void preDestroy").append(n).append("() {\n        Log.add(\"")
                .append(n).append(".preDestroy@\" + Log.self(this));\n    }\n");
        }
        if (bean.selfInterceptor()) {
            out.append("\n    @AroundInvoke\n    Object around").append(n)
                .append("(InvocationContext ctx) throws Exception {\n        Log.add(\"").append(n)
                .append(".around>\" + ctx.getMethod().getName());\n")
                .append("        try {\n            return ctx.proceed();\n        } finally {\n            Log.add(\"")
                .append(n).append(".around<\");\n        }\n    }\n");
        }

        for (ProducerDef producer : bean.producers()) {
            producer(out, producer);
        }
        for (ObserverDef observer : bean.observers()) {
            observer(out, bean, observer);
        }
        out.append("}\n");
        return out.toString();
    }

    private static void producer(StringBuilder out, ProducerDef p) {
        out.append("\n    @Produces\n");
        if (p.scope() != Scope.DEPENDENT) {
            out.append("    @").append(p.scope().annotation).append('\n');
        }
        p.qualifiers().forEach(q -> out.append("    ").append(q.annotation()).append('\n'));
        if (p.typed() != null) {
            out.append("    ").append(typed(p.typed()));
        }
        if (p.alternative()) {
            out.append("    @Alternative\n");
        }
        if (p.priority() != null) {
            out.append("    @Priority(").append(p.priority()).append(")\n");
        }
        String modifiers = p.isStatic() ? "static " : "";
        if (p.field()) {
            out.append("    ").append(modifiers).append(p.type()).append(' ').append(p.name()).append(" = new ")
                .append(implOf(p.type())).append("(\"").append(p.name()).append("\");\n");
        } else {
            out.append("    ").append(modifiers).append(p.type()).append(" produce").append(p.name()).append('(')
                .append(p.parameters().stream().map(ip -> annotations(ip.qualifiers()) + ip.type() + " " + ip.name())
                    .collect(Collectors.joining(", ")))
                .append(") {\n");
            StringBuilder id = new StringBuilder("\"" + p.name());
            if (!p.parameters().isEmpty()) {
                id.append("(\"");
                boolean first = true;
                for (InjectionPointDef ip : p.parameters()) {
                    id.append(first ? " + " : " + \",\" + ").append("Log.call(() -> ").append(ip.name())
                        .append(".id())");
                    first = false;
                }
                id.append(" + \")\"");
            } else {
                id.append('"');
            }
            out.append("        Log.add(\"produce ").append(p.name()).append("\");\n")
                .append("        return new ").append(implOf(p.type())).append('(').append(id).append(");\n    }\n");
        }
        if (p.disposer()) {
            out.append("\n    void dispose").append(p.name()).append("(@Disposes ").append(annotations(p.qualifiers()))
                .append(p.type()).append(" product) {\n        Log.add(\"dispose ").append(p.name())
                .append(" \" + product.id());\n    }\n");
        }
    }

    private static void observer(StringBuilder out, BeanDef bean, ObserverDef o) {
        String priority = o.priority() == null ? DEFAULT_OBSERVER_PRIORITY : o.priority().toString();
        out.append("\n    ").append(o.isStatic() ? "static " : "").append("void observe").append(o.name()).append("(@Observes");
        if (o.ifExists()) {
            out.append("(notifyObserver = Reception.IF_EXISTS)");
        }
        out.append(' ');
        if (o.priority() != null) {
            out.append("@Priority(").append(o.priority()).append(") ");
        }
        out.append(annotations(o.qualifiers())).append(o.eventType()).append(" event) {\n");
        String self = o.isStatic() ? "\"" + bean.name() + "\"" : "Log.self(this)";
        out.append("        Log.add(\"p=").append(priority).append(' ').append(o.name()).append("@\" + ").append(self)
            .append(" + \" \" + Log.ev(event));\n    }\n");
    }

    private static String interceptor(InterceptorDef i) {
        StringBuilder out = new StringBuilder();
        out.append("@Interceptor\n");
        i.bindings().forEach(b -> out.append('@').append(b).append('\n'));
        out.append("@Priority(").append(i.priority()).append(")\n");
        out.append("public class ").append(i.name()).append(" {\n");
        String n = i.name();
        if (i.aroundInvoke()) {
            out.append("\n    @AroundInvoke\n    Object aroundInvoke(InvocationContext ctx) throws Exception {\n")
                .append("        Log.add(\"").append(n).append(">\" + ctx.getMethod().getName());\n")
                .append("        try {\n            return ctx.proceed();\n        } finally {\n")
                .append("            Log.add(\"").append(n).append("<\");\n        }\n    }\n");
        }
        if (i.postConstruct()) {
            out.append("\n    @PostConstruct\n    void postConstruct(InvocationContext ctx) throws Exception {\n")
                .append("        Log.add(\"").append(n).append(".postConstruct@\" + Log.self(ctx.getTarget()));\n")
                .append("        ctx.proceed();\n    }\n");
        }
        if (i.preDestroy()) {
            out.append("\n    @PreDestroy\n    void preDestroy(InvocationContext ctx) throws Exception {\n")
                .append("        Log.add(\"").append(n).append(".preDestroy@\" + Log.self(ctx.getTarget()));\n")
                .append("        ctx.proceed();\n    }\n");
        }
        out.append("}\n");
        return out.toString();
    }

    private static String decorator(DecoratorDef d) {
        return "@Decorator\n@Priority(" + d.priority() + ")\n"
            + "public class " + d.name() + " implements " + d.type() + " {\n\n"
            + "    @Inject\n    @Delegate\n    " + annotations(d.delegateQualifiers()) + d.type() + " delegate;\n\n"
            + "    @Override\n    public String id() {\n        return \"" + d.name() + "(\" + delegate.id() + \")\";\n"
            + "    }\n}\n";
    }

    private static String driver(Scenario scenario) {
        StringBuilder out = new StringBuilder();
        out.append("public final class Driver {\n\n    private Driver() {\n    }\n\n")
            .append("    public static List<String> run(jakarta.enterprise.inject.se.SeContainer container) {\n");
        for (Probe probe : scenario.probes()) {
            out.append("        Log.add(\"#").append(probe.name()).append("\");\n");
            switch (probe.kind()) {
                case SELECT -> {
                    out.append("        try {\n")
                        .append("            var instance = container.select(").append(selectType(probe.type()))
                        .append(literals(probe.qualifiers())).append(");\n")
                        .append("            Log.add(\"unsatisfied=\" + instance.isUnsatisfied() + \" ambiguous=\" + instance.isAmbiguous());\n")
                        .append("            var got = instance.get();\n")
                        .append("            Log.add(\"get=\" + got.id());\n");
                    if (probe.destroy()) {
                        out.append("            instance.destroy(got);\n            Log.add(\"destroyed\");\n");
                    }
                    out.append("        } catch (Throwable t) {\n            Log.add(\"!\" + Log.kind(t));\n        }\n");
                }
                case ITERATE -> out.append("        Log.unordered(() -> {\n")
                    .append("            for (var each : container.select(").append(selectType(probe.type()))
                    .append(literals(probe.qualifiers())).append(")) {\n")
                    .append("                Log.add(\"each=\" + Log.call(() -> each.id()));\n")
                    .append("            }\n        });\n");
                case DESCRIBE -> out.append("        Log.add(Log.call(() -> ((Probe) container.select(")
                    .append(selectType(probe.type())).append(", jakarta.enterprise.inject.Any.Literal.INSTANCE")
                    .append(").get()).describe()));\n");
                case FIRE -> {
                    String type = probe.type();
                    String payload = type.contains("<") ? "new " + type + "()" : "new " + type + "()";
                    out.append("        Log.notification(() -> container.getBeanManager().getEvent().select(")
                        .append(selectType(type)).append(literals(probe.qualifiers())).append(").fire(")
                        .append(payload).append("));\n");
                }
                default -> throw new IllegalStateException(probe.kind().name());
            }
        }
        out.append("        Log.add(\"#shutdown\");\n")
            .append("        Log.unordered(container::close);\n")
            .append("        return Log.drain();\n    }\n}\n");
        return out.toString();
    }

    /**
     * The kinds of injection point that are fields of their bean.
     */
    static boolean isField(IpKind kind) {
        return kind == IpKind.FIELD || kind == IpKind.INSTANCE || kind == IpKind.PROVIDER;
    }
}
