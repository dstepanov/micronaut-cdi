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
package io.micronaut.cdi.processor.visitor;

import io.micronaut.cdi.annotation.CdiDisposer;
import io.micronaut.cdi.annotation.CdiProducer;
import io.micronaut.cdi.processor.Cdi;
import io.micronaut.cdi.processor.InjectedParameters;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Executable;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Prototype;
import io.micronaut.core.annotation.AnnotationClassValue;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.annotation.ReflectiveAccess;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.ConstructorElement;
import io.micronaut.inject.ast.Element;
import io.micronaut.inject.ast.ElementQuery;
import io.micronaut.inject.ast.FieldElement;
import io.micronaut.inject.ast.MemberElement;
import io.micronaut.inject.ast.MethodElement;
import io.micronaut.inject.ast.ParameterElement;
import io.micronaut.inject.visitor.TypeElementVisitor;
import io.micronaut.inject.visitor.VisitorContext;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Reads the producer methods and the producer fields of a class as the Micronaut factory it is.
 *
 * <p>A class that produces a bean rather than being one is what Micronaut calls a factory, and the member that
 * produces it is what Micronaut calls a bean method or a bean field. The two models line up closely enough that
 * the whole of the reading is done here, while the class is compiled: the class is annotated as a factory, each
 * producer as a bean of the scope it declares — the dependent pseudo-scope when it declares none, as the
 * specification says — and Micronaut generates a bean definition for each of them as it would for one written its
 * own way.</p>
 *
 * <p>A disposer method is resolved here too. The specification declares it beside the producer, on the same class,
 * and matches it to the producer by the type and the qualifiers of its {@code Disposes} parameter; that search is
 * done now rather than at runtime, and what it found is recorded on the producer with {@link CdiDisposer} for
 * {@link io.micronaut.cdi.runtime.DisposerInvoker} to invoke as the produced bean is destroyed.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class ProducerVisitor implements TypeElementVisitor<Object, Object> {

    /**
     * The qualifiers that take no part in matching a disposer to its producer.
     */
    private static final Set<String> NOT_COMPARED = Set.of(
        Cdi.DEFAULT,
        Cdi.ANY,
        "io.micronaut.context.annotation.Primary"
    );

    /**
     * The Micronaut scopes a producer may carry, which is what the scope of the specification it declares has
     * already been read as by the time this runs.
     */
    private static final Set<String> SCOPES = Set.of(
        "io.micronaut.context.annotation.Prototype",
        "io.micronaut.cdi.annotation.CdiScope",
        "io.micronaut.cdi.annotation.CdiApplicationScope",
        "io.micronaut.cdi.annotation.CdiRequestScope",
        "jakarta.inject.Singleton",
        "io.micronaut.context.annotation.Context"
    );

    @Override
    public VisitorKind getVisitorKind() {
        return VisitorKind.ISOLATING;
    }

    /**
     * Runs after a class has been decided to be a bean, and before what qualifies it is worked out.
     *
     * @return The order
     */
    @Override
    public int getOrder() {
        return LOWEST_PRECEDENCE - 300;
    }

    @Override
    public Set<String> getSupportedAnnotationNames() {
        return Set.of(Cdi.PRODUCES, Cdi.DISPOSES);
    }

    @Override
    public void visitClass(ClassElement element, VisitorContext context) {
        // section 3.3.6: a producer or disposer is not inherited — it belongs to the class that declares it,
        // and a subclass that wants one declares its own
        List<MethodElement> methods = element.getEnclosedElements(ElementQuery.ALL_METHODS.onlyDeclared());
        List<MemberElement> producers = new ArrayList<>();
        methods.stream().filter(m -> m.hasDeclaredAnnotation(Cdi.PRODUCES)).forEach(producers::add);
        element.getEnclosedElements(ElementQuery.ALL_FIELDS.onlyDeclared())
            .stream()
            .filter(f -> f.hasDeclaredAnnotation(Cdi.PRODUCES))
            .forEach(producers::add);

        List<MethodElement> disposers = methods.stream()
            .filter(ProducerVisitor::isDisposer)
            .toList();

        if (!isABean(element)) {
            // a producer declared by a class that is not a bean is not a producer, and what it would have
            // produced is not a bean either
            return;
        }
        boolean interceptorClass = element.hasDeclaredAnnotation("jakarta.interceptor.Interceptor");
        for (MethodElement disposer : disposers) {
            checkDisposer(disposer, interceptorClass, context);
        }
        if (producers.isEmpty()) {
            if (!interceptorClass && !disposers.isEmpty()) {
                // section 3.3.7: a disposer belongs to a producer of the same class, and this class has none
                context.fail("No producer of this class produces what this disposer method disposes of "
                    + "(section 3.3.7)", disposers.get(0));
            }
            return;
        }

        // the class produces beans rather than only being one, which is what Micronaut calls a factory
        element.annotate(Factory.class);

        java.util.Set<MethodElement> matchedDisposers = new java.util.HashSet<>();
        for (MemberElement producer : producers) {
            if (interceptorClass) {
                // section 3.3.2/3.4.2: an interceptor declares no producers
                context.fail("An interceptor may not declare a producer", producer);
            }
            checkProducedType(producer, context);
            if (producer instanceof MethodElement producerMethod) {
                for (ParameterElement parameter : producerMethod.getParameters()) {
                    if (parameter.hasDeclaredAnnotation(Cdi.OBSERVES)
                        || parameter.hasDeclaredAnnotation(Cdi.OBSERVES_ASYNC)
                        || parameter.hasDeclaredAnnotation(Cdi.DISPOSES)) {
                        // section 3.3.6: a producer method's parameters are injection points, and one that
                        // tries to observe or dispose is the definition error the kit deploys
                        context.fail("A producer method parameter may not be annotated Observes, "
                            + "ObservesAsync or Disposes (section 3.3.6)", parameter);
                    }
                }
            }
            producer.annotate(Bean.class);
            selectIfAlternative(producer, element);
            // what produced the bean is recorded so that the container can report it without working it out
            boolean isField = producer instanceof FieldElement;
            boolean isRaw = producedType(producer).isRawType();
            producer.annotate(CdiProducer.class, builder -> builder
                .member("declaringType", new AnnotationClassValue<>(element.getName()))
                .member("member", producer.getName())
                .member("field", isField)
                .member("raw", isRaw));
            // a producer is dependent scoped unless it declares a scope of its own, and a Micronaut prototype is
            // the dependent pseudo-scope. The scope is written onto the producer rather than left to default to
            // it, because the annotation metadata of a producer carries what its class declares as well: without
            // one of its own the produced bean would be resolved in the scope of the class that produces it
            if (!declaresAScope(producer)) {
                producer.annotate(Prototype.class);
                // written on the member so that it shadows the scope of the class that declares the producer,
                // which the produced bean's metadata carries as well
                producer.annotate(io.micronaut.cdi.annotation.CdiScope.class,
                    builder -> builder.value(Cdi.DEPENDENT));
            } else if (!producer.hasDeclaredAnnotation("io.micronaut.cdi.annotation.CdiScope")) {
                // the scope came through a stereotype the producer declares: written onto the member itself so
                // that it shadows the scope of the class that declares the producer (section 2.6.1)
                io.micronaut.core.annotation.AnnotationMetadata metadata = producer.getAnnotationMetadata();
                String stereotypeScope = null;
                boolean normal = false;
                if (metadata.hasDeclaredStereotype("io.micronaut.cdi.annotation.CdiRequestScope")) {
                    stereotypeScope = Cdi.REQUEST_SCOPED;
                    normal = true;
                } else if (metadata.hasDeclaredStereotype("io.micronaut.cdi.annotation.CdiApplicationScope")) {
                    stereotypeScope = Cdi.APPLICATION_SCOPED;
                    normal = true;
                } else if (metadata.hasDeclaredStereotype("jakarta.inject.Singleton")) {
                    stereotypeScope = "jakarta.inject.Singleton";
                }
                if (stereotypeScope != null) {
                    String value = stereotypeScope;
                    boolean isNormal = normal;
                    producer.annotate("io.micronaut.cdi.annotation.CdiScope",
                        builder -> builder.value(value).member("normal", isNormal));
                }
            }
            // section 3.2.2: a producer of a dependent instance may return null, and the null is what is
            // injected — the definition says so, so that resolution hands the null on instead of failing
            producer.annotate(io.micronaut.core.annotation.AnnotationUtil.NULLABLE);
            allowReflectionIfNeeded(producer);
            if (producer instanceof MethodElement producerMethod) {
                InjectedParameters.readAsInjectionPoints(producerMethod);
            }
            MethodElement disposer = findDisposer(producer, disposers, context);
            if (disposer != null) {
                matchedDisposers.add(disposer);
                if (disposer.isStatic()) {
                    // Micronaut writes no executable method for a static method, so a static disposer is
                    // dispatched reflectively
                    disposer.annotate(ReflectiveAccess.class);
                } else {
                    // the disposer is invoked through the executable method Micronaut generates for it
                    disposer.annotate(Executable.class);
                    InjectedParameters.readAsInjectionPoints(disposer);
                }
                boolean staticDisposer = disposer.isStatic();
                producer.annotate(CdiDisposer.class, builder -> builder
                    .member("declaringType", new AnnotationClassValue<>(element.getName()))
                    .member("method", disposer.getName())
                    .member("disposedParameter", disposedParameter(disposer))
                    .member("staticMethod", staticDisposer));
            }
        }
        for (MethodElement disposer : disposers) {
            if (!matchedDisposers.contains(disposer)) {
                // section 3.3.7: every disposer belongs to a producer of the same class, and one that matches
                // none disposes of nothing
                context.fail("No producer of this class produces what this disposer method disposes of "
                    + "(section 3.3.7)", disposer);
            }
        }
    }

    /**
     * The definition rules a disposer carries on its own: one disposed parameter, and not an initializer.
     */
    private static void checkDisposer(MethodElement disposer, boolean interceptorClass, VisitorContext context) {
        if (interceptorClass) {
            context.fail("An interceptor may not declare a disposer method", disposer);
        }
        int disposed = 0;
        for (ParameterElement parameter : disposer.getParameters()) {
            if (parameter.hasDeclaredAnnotation(Cdi.DISPOSES)) {
                disposed++;
            }
        }
        if (disposed > 1) {
            context.fail("A disposer method declares exactly one disposed parameter (section 3.3.7)", disposer);
        }
    }

    /**
     * The rules of sections 3.3.2 and 3.4.2 on what a producer may say it produces: never a type variable or
     * anything containing a wildcard, and a type containing a variable only for a dependent instance —
     * anything else is compiled with variables it will never learn the values of.
     */
    private static void checkProducedType(MemberElement producer, VisitorContext context) {
        ClassElement produced = producedType(producer);
        String what = producer instanceof FieldElement ? "field" : "method";
        if (produced.isGenericPlaceholder()) {
            context.fail("A producer " + what + " type may not be a type variable (section 3.3.2)", producer);
            return;
        }
        ClassElement component = produced;
        while (component.isArray()) {
            component = component.fromArray();
        }
        if (component.isGenericPlaceholder()) {
            context.fail("A producer " + what + " may not produce an array of a type variable "
                + "(section 3.3.2)", producer);
            return;
        }
        if (produced.isRawType()) {
            return;
        }
        if (containsWildcard(component)) {
            context.fail("A producer " + what + " type may not contain a wildcard (section 3.3.2)", producer);
            return;
        }
        if (containsVariable(component)) {
            if (declaresAScope(producer)) {
                context.fail("A producer " + what + " whose type contains a type variable produces a "
                    + "dependent instance or nothing (section 3.3.2)", producer);
                return;
            }
            // legal, and the variables are recorded so resolution can read them (section 2.4.2.1)
            recordProducedVariables(component, producer);
        }
    }

    private static boolean containsWildcard(ClassElement type) {
        for (ClassElement argument : type.getTypeArguments().values()) {
            if (argument instanceof io.micronaut.inject.ast.WildcardElement) {
                return true;
            }
            if (!argument.isGenericPlaceholder() && !argument.isRawType() && containsWildcard(argument)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsVariable(ClassElement type) {
        for (ClassElement argument : type.getTypeArguments().values()) {
            if (argument.isGenericPlaceholder()) {
                return true;
            }
            if (!argument.isRawType() && containsVariable(argument)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Leaves the variables of the produced type where resolution can read them, the way an injection point's
     * are: the compiled argument erases a variable to its bound, and section 2.4.2.1 matches a variable
     * differently from the type it erases to.
     */
    private static void recordProducedVariables(ClassElement produced, MemberElement producer) {
        java.util.List<String> recorded = new java.util.ArrayList<>();
        int position = 0;
        for (ClassElement argument : produced.getTypeArguments().values()) {
            if (argument instanceof io.micronaut.inject.ast.GenericPlaceholderElement placeholder) {
                java.util.List<String> bounds = new java.util.ArrayList<>();
                for (ClassElement bound : placeholder.getBounds()) {
                    bounds.add(bound.getName());
                }
                recorded.add(position + "=var:" + (bounds.isEmpty() ? "java.lang.Object"
                    : String.join(",", bounds)));
            }
            position++;
        }
        if (!recorded.isEmpty()) {
            String[] entries = recorded.toArray(new String[0]);
            producer.annotate("io.micronaut.cdi.annotation.CdiGenericVariables",
                builder -> builder.member("value", entries));
        }
    }

    /**
     * Selects a producer that is an alternative, the way section 2.1.7 selects a bean.
     *
     * <p>A producer is an alternative when it says so itself or when the class that declares it is one, and it
     * is selected by a priority: its own, or failing that the class's. A selected one is ordered by that
     * priority so that the highest wins resolution; an alternative producer that is not selected produces
     * nothing at all.</p>
     */
    private static void selectIfAlternative(MemberElement producer, ClassElement element) {
        boolean alternative = producer.hasDeclaredAnnotation(Cdi.ALTERNATIVE)
            || element.getAnnotationMetadata().hasStereotype(Cdi.ALTERNATIVE);
        if (!alternative) {
            return;
        }
        Integer priority = Cdi.priorityOf(producer.getAnnotationMetadata());
        if (priority == null) {
            // a field's metadata carries only what the field declares, so the class the producer belongs to is
            // asked as well: its priority selects its producers, the specification says
            priority = Cdi.priorityOf(element.getAnnotationMetadata());
        }
        if (priority == null) {
            // an alternative producer that no priority selected produces nothing here, but the SE bootstrap
            // may still select it as the container is built; the class's own selection has already decided
            // whether the whole factory is a bean, so only the member is made conditional here
            if (!element.getAnnotationMetadata().hasStereotype(Cdi.ALTERNATIVE)) {
                java.util.List<String> stereotypes = producer.getAnnotationMetadata()
                    .getAnnotationNamesByStereotype(Cdi.ALTERNATIVE).stream()
                    .filter(name -> !name.equals(Cdi.ALTERNATIVE))
                    .toList();
                producer.annotate(io.micronaut.context.annotation.Requires.class, builder -> builder
                    .member("condition", new AnnotationClassValue<>(
                        "io.micronaut.cdi.annotation.UnselectedAlternative")));
                String className = element.getName();
                producer.annotate("io.micronaut.cdi.annotation.CdiSelectableAlternative", builder -> {
                    builder.value(className);
                    builder.member("producer", true);
                    if (!stereotypes.isEmpty()) {
                        builder.member("stereotypes", stereotypes.toArray(new String[0]));
                    }
                });
            }
            return;
        }
        // the order is the priority negated, written over the one Micronaut read from the priority itself:
        // a Micronaut order prefers the lowest, the specification the highest
        int order = -priority;
        producer.removeAnnotation("io.micronaut.core.annotation.Order");
        producer.annotate("io.micronaut.core.annotation.Order", builder -> builder.value(order));
    }

    /**
     * Whether the class that declares the producers is a bean.
     *
     * <p>Section 2.2.5 has a managed bean declare either a constructor taking no parameters or one annotated
     * {@code Inject}, and a class that declares neither is not a bean however it is annotated. That matters here
     * because a producer belongs to the bean that declares it: where there is no bean, there is no producer, and
     * nothing it would have produced.</p>
     */
    private static boolean isABean(ClassElement element) {
        // deliberately more lenient than Lite's annotated discovery: a producer compiles wherever it is
        // declared, because whether its class is a bean of a given deployment is decided per deployment — the
        // SE bootstrap's addBeanClasses makes a class with no bean defining annotation a bean by fiat
        List<ConstructorElement> constructors = element.getEnclosedElements(ElementQuery.CONSTRUCTORS);
        if (constructors.isEmpty()) {
            // the implicit constructor takes no parameters
            return true;
        }
        for (ConstructorElement constructor : constructors) {
            if (constructor.getParameters().length == 0
                || constructor.hasDeclaredAnnotation("jakarta.inject.Inject")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether the producer declares a scope of its own, which is the scope the bean it produces belongs to.
     */
    private static boolean declaresANormalScope(MemberElement producer) {
        return producer.hasDeclaredAnnotation("io.micronaut.cdi.annotation.CdiRequestScope")
            || producer.hasDeclaredAnnotation("io.micronaut.cdi.annotation.CdiApplicationScope")
            || producer.getAnnotationMetadata()
                .hasDeclaredStereotype("io.micronaut.cdi.annotation.CdiRequestScope")
            || producer.getAnnotationMetadata()
                .hasDeclaredStereotype("io.micronaut.cdi.annotation.CdiApplicationScope");
    }

    private static boolean declaresAScope(MemberElement producer) {
        for (String scope : SCOPES) {
            if (producer.hasDeclaredAnnotation(scope)) {
                return true;
            }
            // a stereotype the producer declares carries its scope with it (section 2.6.1)
            if (producer.getAnnotationMetadata().hasDeclaredStereotype(scope)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Lets a producer that is not accessible be read anyway.
     *
     * <p>The specification allows a producer to be private, and a private member cannot be read from the bean
     * definition Micronaut generates beside it. Saying so is what {@code ReflectiveAccess} is for: the member is
     * read reflectively, and only that member is. Everything else about the bean goes on being resolved the way
     * it was compiled, so what the author wrote decides where reflection is used rather than the module deciding
     * it for them.</p>
     */
    private static void allowReflectionIfNeeded(MemberElement producer) {
        if (producer.isPrivate() && !producer.hasDeclaredAnnotation(ReflectiveAccess.class)) {
            producer.annotate(ReflectiveAccess.class);
        }
    }

    private static boolean isDisposer(MethodElement method) {
        for (ParameterElement parameter : method.getParameters()) {
            if (parameter.hasDeclaredAnnotation(Cdi.DISPOSES)) {
                return true;
            }
        }
        return false;
    }

    private static int disposedParameter(MethodElement disposer) {
        ParameterElement[] parameters = disposer.getParameters();
        for (int i = 0; i < parameters.length; i++) {
            if (parameters[i].hasDeclaredAnnotation(Cdi.DISPOSES)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Finds the disposer of a producer among the disposers the same class declares, which is where the
     * specification has it declared.
     *
     * <p>A disposer disposes of a producer when the type of its disposed parameter is the type the producer
     * produces and their qualifiers are the same, which is the resolution rule of the specification narrowed to
     * the one class the two are declared on.</p>
     */
    /**
     * Whether the produced type's arguments fit the disposed parameter's: a disposer of {@code List<String>}
     * does not dispose of what a producer of {@code List<Integer>} produces, though the two erase alike. A
     * raw side fits any parameterization, and a variable or wildcard on the disposer's side admits what the
     * producer wrote.
     */
    private static boolean typeArgumentsCompatible(ClassElement produced, ClassElement disposed) {
        java.util.Collection<ClassElement> producedArguments = produced.getTypeArguments().values();
        java.util.Collection<ClassElement> disposedArguments = disposed.getTypeArguments().values();
        if (producedArguments.isEmpty() || disposedArguments.isEmpty()
            || producedArguments.size() != disposedArguments.size()) {
            return true;
        }
        java.util.Iterator<ClassElement> producedEach = producedArguments.iterator();
        java.util.Iterator<ClassElement> disposedEach = disposedArguments.iterator();
        while (producedEach.hasNext()) {
            ClassElement producedArgument = producedEach.next();
            ClassElement disposedArgument = disposedEach.next();
            if (disposedArgument.isGenericPlaceholder() || disposedArgument.isWildcard()
                || producedArgument.isGenericPlaceholder() || producedArgument.isWildcard()) {
                continue;
            }
            if (!producedArgument.getName().equals(disposedArgument.getName())
                || !typeArgumentsCompatible(producedArgument, disposedArgument)) {
                return false;
            }
        }
        return true;
    }

    private @Nullable MethodElement findDisposer(MemberElement producer,
                                                 List<MethodElement> disposers,
                                                 VisitorContext context) {
        ClassElement produced = producedType(producer);
        MethodElement found = null;
        for (MethodElement disposer : disposers) {
            ParameterElement disposed = disposer.getParameters()[disposedParameter(disposer)];
            // section 3.3.7 matches the disposed parameter by the rules of typesafe resolution: a disposer of
            // the supertype disposes of what a producer of the subtype produces — and the type arguments take
            // part, which Micronaut's erased assignability alone would miss
            if (!produced.isAssignable(disposed.getGenericType())
                || !typeArgumentsCompatible(produced, disposed.getGenericType())) {
                continue;
            }
            // a disposed parameter qualified Any disposes of what every producer of the type produced, however
            // those producers are qualified; anything else has to be qualified the same way the producer is
            if (!disposed.hasDeclaredAnnotation(Cdi.ANY)
                && !disposed.hasDeclaredAnnotation("io.micronaut.cdi.annotation.CdiAny")
                && !qualifiers(disposed).equals(qualifiers(producer))) {
                continue;
            }
            if (found != null) {
                context.fail("More than one disposer method disposes of what this producer produces", producer);
                return null;
            }
            found = disposer;
        }
        return found;
    }

    private static ClassElement producedType(MemberElement producer) {
        if (producer instanceof MethodElement method) {
            return method.getGenericReturnType();
        }
        return ((FieldElement) producer).getGenericType();
    }

    /**
     * The qualifiers an element declares, as the names of the qualifier annotations mapped to their members.
     *
     * <p>They are read into a sorted map so that two elements qualified the same way compare equal whichever
     * order the qualifiers were written in. Only the qualifiers the element declares itself are read: the
     * annotation metadata of a member carries what its class declares as well, and a disposer parameter is
     * compared against a producer of another class as often as not.</p>
     *
     * <p>The default qualifier is left out of the comparison, along with the Micronaut annotation that goes with
     * it. It is given to a bean rather than written on one, and a disposer parameter is not a bean, so counting
     * it would have every disposer fail to match the producer it was written for.</p>
     */
    private static Map<String, Map<CharSequence, Object>> qualifiers(Element element) {
        Map<String, Map<CharSequence, Object>> qualifiers = new TreeMap<>();
        for (String name : element.getAnnotationMetadata().getAnnotationNamesByStereotype(Cdi.QUALIFIER)) {
            if (NOT_COMPARED.contains(name) || !element.hasDeclaredAnnotation(name)) {
                continue;
            }
            AnnotationValue<?> annotation = element.getAnnotation(name);
            if (annotation != null) {
                qualifiers.put(name, annotation.getValues());
            }
        }
        return qualifiers;
    }
}
