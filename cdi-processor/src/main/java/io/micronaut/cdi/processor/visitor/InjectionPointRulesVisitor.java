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

import io.micronaut.cdi.processor.Cdi;
import io.micronaut.core.annotation.AnnotationUtil;
import io.micronaut.core.annotation.Internal;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.ConstructorElement;
import io.micronaut.inject.ast.ElementQuery;
import io.micronaut.inject.ast.FieldElement;
import io.micronaut.inject.ast.MethodElement;
import io.micronaut.inject.ast.ParameterElement;
import io.micronaut.inject.visitor.TypeElementVisitor;
import io.micronaut.inject.visitor.VisitorContext;

/**
 * The definition errors of section 2.5 that live on an injection point, detected as the class compiles: a
 * container detects them as it deploys, and deployment is compilation here.
 *
 * <ul>
 * <li>an injection point whose declared type is a type variable (2.5.2.1);</li>
 * <li>a raw {@code Instance}, which names no type to look up (4.10);</li>
 * <li>{@code @Named} without a value somewhere the default — the name of the field — cannot apply
 * (2.6.1);</li>
 * <li>injection point metadata in a bean of a normal scope, which outlives any one injection point
 * (2.5.2.5), or in a disposer method, whose parameter is being destroyed rather than injected
 * anywhere.</li>
 * </ul>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class InjectionPointRulesVisitor implements TypeElementVisitor<Object, Object> {

    private static final String INSTANCE = "jakarta.enterprise.inject.Instance";
    private static final String EVENT = "jakarta.enterprise.event.Event";
    private static final String INJECTION_POINT = "jakarta.enterprise.inject.spi.InjectionPoint";

    @Override
    public VisitorKind getVisitorKind() {
        return VisitorKind.ISOLATING;
    }

    @Override
    public int getOrder() {
        return LOWEST_PRECEDENCE - 150;
    }

    @Override
    public void visitClass(ClassElement element, VisitorContext context) {
        if (element.isAbstract() || element.isInterface()) {
            // an abstract class is not a bean; its members become injection points through the concrete
            // classes that extend it, which are visited with the abstract class's variables resolved —
            // recording the unresolved form here would shadow that resolution
            return;
        }
        boolean normalScoped = isNormalScoped(element);
        // a type variable of a generic bean class is resolved by whoever extends it; one declared anywhere
        // else has nothing to resolve it and is the definition error of section 2.5.2.1
        boolean genericClass = !element.getDeclaredGenericPlaceholders().isEmpty();
        int injectedConstructors = 0;
        for (ConstructorElement constructor : element.getEnclosedElements(ElementQuery.CONSTRUCTORS)) {
            for (ParameterElement parameter : constructor.getParameters()) {
                if (parameter.hasDeclaredAnnotation(Cdi.OBSERVES)
                    || parameter.hasDeclaredAnnotation(Cdi.OBSERVES_ASYNC)
                    || parameter.hasDeclaredAnnotation(Cdi.DISPOSES)) {
                    // section 3.9: a bean constructor's parameters are all injection points — one that tries
                    // to observe or dispose is the definition error the kit deploys
                    context.fail("A bean constructor parameter may not be annotated Observes, ObservesAsync "
                        + "or Disposes (section 3.9)", parameter);
                }
            }
            if (isInjected(constructor)) {
                injectedConstructors++;
                if (injectedConstructors > 1) {
                    context.fail("A bean class may declare at most one constructor annotated Inject "
                        + "(section 3.9)", constructor);
                }
                for (ParameterElement parameter : constructor.getParameters()) {
                    checkParameter(parameter, constructor, normalScoped, false, false, genericClass,
                        element, null, context);
                }
            }
        }
        for (FieldElement field : element.getEnclosedElements(ElementQuery.ALL_FIELDS.onlyDeclared())) {
            if (isInjected(field)) {
                if (field.hasDeclaredAnnotation(Cdi.PRODUCES)) {
                    // section 3.4.1: a field is a producer or an injection point, never both
                    context.fail("A field may not be annotated both Inject and Produces (section 3.4.1)",
                        field);
                }
                if (field.isFinal()) {
                    // CDI 4.1 section 3.6: an injected field is a non-static, non-final field, and Jakarta Dependency
                    // Injection has injectable fields not final; Micronaut would leave the field as it was
                    // initialized rather than inject it
                    context.fail("An injected field may not be final (CDI 4.1 section 3.6)", field);
                }
                checkType(field.getGenericField(), field, normalScoped, false, false, genericClass,
                    element, null, context);
            }
        }
        for (MethodElement method : element.getEnclosedElements(ElementQuery.ALL_METHODS.onlyDeclared())) {
            boolean injected = isInjected(method);
            boolean producer = method.hasDeclaredAnnotation(Cdi.PRODUCES);
            boolean disposer = false;
            boolean observer = false;
            for (ParameterElement parameter : method.getParameters()) {
                disposer |= parameter.hasDeclaredAnnotation(Cdi.DISPOSES);
                observer |= parameter.hasDeclaredAnnotation(Cdi.OBSERVES)
                    || parameter.hasDeclaredAnnotation(Cdi.OBSERVES_ASYNC);
            }
            if (!injected && !producer && !disposer && !observer) {
                continue;
            }
            if (injected && disposer) {
                // section 3.3.7: an initializer method is not a disposer
                context.fail("A method may not be annotated Inject and declare a disposed parameter "
                    + "(section 3.3.7)", method);
            }
            if (injected && !producer && !disposer && !observer
                && !method.getDeclaredTypeVariables().isEmpty()) {
                // section 3.10: an initializer method has no type parameters of its own
                context.fail("An initializer method may not be generic (section 3.10)", method);
            }
            String allowedMetadataType = null;
            if (producer) {
                allowedMetadataType = method.getGenericReturnType().getName();
            } else if (disposer) {
                for (ParameterElement parameter : method.getParameters()) {
                    if (parameter.hasDeclaredAnnotation(Cdi.DISPOSES)) {
                        allowedMetadataType = parameter.getGenericType().getName();
                        break;
                    }
                }
            }
            for (ParameterElement parameter : method.getParameters()) {
                if (parameter.hasDeclaredAnnotation(Cdi.DISPOSES)
                    || parameter.hasDeclaredAnnotation(Cdi.OBSERVES)
                    || parameter.hasDeclaredAnnotation(Cdi.OBSERVES_ASYNC)) {
                    // the disposed parameter is being destroyed and the observed one is the event: neither is
                    // an injection point
                    continue;
                }
                checkParameter(parameter, method, producer ? isNormalScopedProducer(method) : normalScoped,
                    disposer, observer, genericClass, element, allowedMetadataType, context);
            }
        }
    }

    private void checkParameter(ParameterElement parameter, MethodElement method, boolean normalScoped,
                                boolean disposer, boolean observer, boolean genericClass,
                                ClassElement declaring, @org.jspecify.annotations.Nullable String allowedMetadataType,
                                VisitorContext context) {
        String named = null;
        if (parameter.hasDeclaredAnnotation(AnnotationUtil.NAMED)) {
            named = parameter.stringValue(AnnotationUtil.NAMED).orElse("");
        } else if (parameter.hasDeclaredAnnotation("jakarta.inject.Named")) {
            named = parameter.stringValue("jakarta.inject.Named").orElse("");
        }
        if ("".equals(named)) {
            // a field's name is the default; a parameter has no name at runtime to default to
            context.fail("A parameter injection point that declares Named must name the bean it asks for: "
                + method.getDescription(true), parameter);
            return;
        }
        checkType(parameter.getGenericType(), parameter, normalScoped, disposer, observer, genericClass,
            declaring, allowedMetadataType, context);
    }

    private void checkType(ClassElement type, io.micronaut.inject.ast.Element at, boolean normalScoped,
                           boolean disposer, boolean observer, boolean genericClass,
                           ClassElement declaring, @org.jspecify.annotations.Nullable String allowedMetadataType,
                           VisitorContext context) {
        if (type.isGenericPlaceholder() && !genericClass) {
            context.fail("An injection point whose declared type is a type variable is a definition error "
                + "(section 2.5.2.1)", at);
            return;
        }
        if ((INSTANCE.equals(type.getName()) || EVENT.equals(type.getName())) && type.isRawType()) {
            context.fail("A raw " + type.getSimpleName() + " names no type: parameterize it with the type it "
                + "carries", at);
            return;
        }
        checkMetadataInjection(type, at, declaring, allowedMetadataType, context);
        if (!observer && "jakarta.enterprise.inject.spi.EventMetadata".equals(type.getName())) {
            // the metadata of an event exists only while an observer method is being notified: everywhere
            // else there is no event to describe
            context.fail("EventMetadata can only be injected into an observer method's parameter "
                + "(section 2.8.4)", at);
            return;
        }
        if (INJECTION_POINT.equals(type.getName())) {
            if (normalScoped) {
                context.fail("A bean of a normal scope outlives any one injection point and cannot be told "
                    + "which it is at (section 2.5.2.5)", at);
            } else if (disposer) {
                context.fail("A disposer method destroys its parameter rather than being injected anywhere, "
                    + "and has no injection point to be told of", at);
            }
        }
    }

    /**
     * The rules of section 9.4, which say who may be told what about themselves: {@code Bean<X>} reaches the
     * bean whose class is X — or a producer or disposer of X — {@code Interceptor} and the intercepted
     * {@code Bean} reach only an interceptor, and the intercepted one says nothing more than {@code Bean<?>}.
     * {@code Decorator} and the decorated {@code Bean} reach only a decorator, which no class is here.
     */
    private void checkMetadataInjection(ClassElement type, io.micronaut.inject.ast.Element at,
                                        ClassElement declaring,
                                        @org.jspecify.annotations.Nullable String allowedMetadataType,
                                        VisitorContext context) {
        String name = type.getName();
        boolean interceptorClass = declaring.hasDeclaredAnnotation("jakarta.interceptor.Interceptor");
        boolean decoratorClass = declaring.hasDeclaredAnnotation("jakarta.decorator.Decorator");
        if ("jakarta.enterprise.inject.spi.Decorator".equals(name)) {
            if (!decoratorClass) {
                context.fail("Decorator metadata reaches only a decorator (section 9.4)", at);
            }
        } else if ("jakarta.enterprise.inject.spi.Bean".equals(name)) {
            java.util.Collection<ClassElement> arguments = type.getTypeArguments().values();
            ClassElement argument = arguments.isEmpty() ? null : arguments.iterator().next();
            if (at.hasDeclaredAnnotation("jakarta.enterprise.inject.Decorated")) {
                if (!decoratorClass) {
                    context.fail("The decorated Bean is the metadata of the bean a decorator wraps, "
                        + "which only a decorator has (section 9.4)", at);
                }
                return;
            }
            if (at.hasDeclaredAnnotation("jakarta.enterprise.inject.Intercepted")) {
                if (!interceptorClass) {
                    context.fail("The intercepted Bean is the metadata of the bean an interceptor wraps, "
                        + "which only an interceptor has (section 9.4)", at);
                } else if (!isUnboundedWildcard(argument)) {
                    context.fail("The intercepted bean may be any bean, and its metadata is asked for as "
                        + "Bean<?> (section 9.4)", at);
                }
                return;
            }
            if (type.isRawType() || argument == null || argument.isGenericPlaceholder()
                || argument instanceof io.micronaut.inject.ast.WildcardElement) {
                return;
            }
            if (!argument.getName().equals(declaring.getName())
                && !argument.getName().equals(allowedMetadataType)) {
                context.fail("A bean may only be told about itself: Bean<" + argument.getSimpleName()
                    + "> is not the metadata of " + declaring.getSimpleName() + " (section 9.4)", at);
            }
        } else if ("jakarta.enterprise.inject.spi.Interceptor".equals(name)) {
            java.util.Collection<ClassElement> arguments = type.getTypeArguments().values();
            ClassElement argument = arguments.isEmpty() ? null : arguments.iterator().next();
            if (!interceptorClass) {
                context.fail("Interceptor metadata reaches only an interceptor (section 9.4)", at);
            } else if (argument != null && !argument.isGenericPlaceholder()
                && !(argument instanceof io.micronaut.inject.ast.WildcardElement)
                && !argument.getName().equals(declaring.getName())) {
                context.fail("An interceptor may only be told about itself (section 9.4)", at);
            }
        }
    }

    private static boolean isUnboundedWildcard(@org.jspecify.annotations.Nullable ClassElement argument) {
        if (!(argument instanceof io.micronaut.inject.ast.WildcardElement wildcard)) {
            return false;
        }
        if (!wildcard.getLowerBounds().isEmpty()) {
            return false;
        }
        java.util.List<? extends ClassElement> uppers = wildcard.getUpperBounds();
        return uppers.isEmpty() || uppers.size() == 1 && "java.lang.Object".equals(uppers.get(0).getName());
    }

    private boolean isInjected(io.micronaut.inject.ast.Element element) {
        return element.hasDeclaredAnnotation(AnnotationUtil.INJECT)
            || element.hasDeclaredAnnotation("jakarta.inject.Inject");
    }

    /**
     * Whether what a producer method produces lives in a normal scope.
     *
     * <p>A produced bean has the scope the producer declares rather than the scope of the class that declares the
     * producer, and the two are different often enough to matter: a dependent instance produced by a method of an
     * application scoped bean may be told which injection point it was produced for (section 2.5.2.5), and
     * reading the class's scope instead would refuse it. The producer carries the scope of the bean it produces
     * by the time this runs, written there by the visitor that reads producers.</p>
     */
    private static boolean isNormalScopedProducer(MethodElement producer) {
        // read from what the member itself declares: the annotation metadata of a member carries what its class
        // declares as well, and the class's scope is the one this must not be answered with
        return producer.getAnnotationMetadata().getDeclaredMetadata()
            .booleanValue("io.micronaut.cdi.annotation.CdiScope", "normal").orElse(false);
    }

    private static boolean isNormalScoped(ClassElement element) {
        if (element.hasStereotype(Cdi.NORMAL_SCOPE)) {
            return true;
        }
        return element.getAnnotationMetadata()
            .booleanValue("io.micronaut.cdi.annotation.CdiScope", "normal")
            .orElse(false);
    }
}
