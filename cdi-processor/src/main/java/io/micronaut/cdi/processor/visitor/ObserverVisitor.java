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

import io.micronaut.cdi.annotation.CdiObserver;
import io.micronaut.cdi.processor.Cdi;
import io.micronaut.cdi.processor.InjectedParameters;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.annotation.ReflectiveAccess;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.ElementQuery;
import io.micronaut.inject.ast.MethodElement;
import io.micronaut.inject.ast.ParameterElement;
import io.micronaut.inject.visitor.TypeElementVisitor;
import io.micronaut.inject.visitor.VisitorContext;

import java.util.OptionalInt;
import java.util.Set;

/**
 * Finds the observer methods of a class and records what the container needs in order to notify them.
 *
 * <p>An observer method is a method with one parameter annotated {@code Observes} or {@code ObservesAsync}, and it
 * is found here rather than at runtime so that notifying it is a direct invocation of an executable method. The
 * validation the specification asks for in section 2.8.4 is done here too, while there is a compiler to report
 * it through: a method that observes twice, or that is also an initializer or a producer, is rejected as it is
 * compiled rather than as it is deployed.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class ObserverVisitor implements TypeElementVisitor<Object, Object> {

    private static final int DEFAULT_PRIORITY = 2500;

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
        return Set.of(Cdi.OBSERVES, Cdi.OBSERVES_ASYNC);
    }

    @Override
    public void visitClass(ClassElement element, VisitorContext context) {
        boolean interceptorClass = element.hasDeclaredAnnotation("jakarta.interceptor.Interceptor");
        for (MethodElement method : element.getEnclosedElements(ElementQuery.ALL_METHODS)) {
            int observed = -1;
            boolean async = false;
            ParameterElement[] parameters = method.getParameters();
            for (int i = 0; i < parameters.length; i++) {
                boolean observes = Cdi.declares(parameters[i], Cdi.OBSERVES);
                boolean observesAsync = Cdi.declares(parameters[i], Cdi.OBSERVES_ASYNC);
                if (!observes && !observesAsync) {
                    continue;
                }
                if (interceptorClass) {
                    // section 2.8: an interceptor has no observer methods
                    context.fail("An interceptor may not declare an observer method", parameters[i]);
                }
                if (observes && observesAsync) {
                    context.fail("A parameter is observed synchronously or asynchronously, not both ways at "
                        + "once", method);
                    return;
                }
                if (observed >= 0) {
                    context.fail("An observer method has one parameter that it observes, and this one has more "
                        + "than one", method);
                    return;
                }
                observed = i;
                async = observesAsync;
            }
            if (observed < 0) {
                continue;
            }
            if (!validate(method, context)) {
                return;
            }
            ParameterElement parameter = parameters[observed];
            AnnotationValue<?> observes = parameter.getAnnotation(async ? Cdi.OBSERVES_ASYNC : Cdi.OBSERVES);
            boolean ifExists = observes != null
                && observes.stringValue("notifyObserver").filter("IF_EXISTS"::equals).isPresent();
            if (ifExists && isDependentScoped(element)) {
                // section 2.8.2: a conditional observer needs an instance that may already exist, and a
                // dependent bean never has one — each resolution is a new instance
                context.fail("A dependent bean has no instance that could already exist, so its observer "
                    + "cannot be conditional (section 2.8.2)", method);
                return;
            }
            // the specification puts the priority on the parameter the method observes, beside the annotation
            // that declares it an observer; a priority on the method itself is read as well, since it is the
            // more obvious place to write one and says the same thing
            int priority = priorityOf(parameter, method);
            if (method.isPrivate() && !method.hasDeclaredAnnotation(ReflectiveAccess.class)) {
                // the specification allows a private observer method, which cannot be notified through an
                // executable method the way an accessible one is. Saying so is what ReflectiveAccess is for, and
                // only that method is notified reflectively
                method.annotate(ReflectiveAccess.class);
            }
            InjectedParameters.readAsInjectionPoints(method);
            int position = observed;
            boolean asynchronous = async;
            boolean isStatic = method.isStatic();
            String during = observes == null ? "IN_PROGRESS"
                : observes.stringValue("during").orElse("IN_PROGRESS");
            method.annotate(CdiObserver.class, builder -> builder
                .member("observedParameter", position)
                .member("async", asynchronous)
                .member("ifExists", ifExists)
                .member("staticMethod", isStatic)
                .member("during", during)
                .member("priority", priority));
        }
    }

    /**
     * Whether the class is in the dependent scope, written either as the specification writes it or as the
     * mapper has already read it.
     */
    private static boolean isDependentScoped(ClassElement element) {
        if (element.hasStereotype(Cdi.DEPENDENT)) {
            return true;
        }
        String scope = element.getAnnotationMetadata()
            .stringValue("io.micronaut.cdi.annotation.CdiScope").orElse(null);
        if (scope != null) {
            return Cdi.DEPENDENT.equals(scope);
        }
        // a bean with no scope at all is dependent by default
        return !element.hasStereotype("jakarta.enterprise.context.NormalScope")
            && !element.hasStereotype("io.micronaut.cdi.annotation.CdiScope");
    }

    /**
     * The priority the observer is notified in.
     *
     * <p>The specification puts it on the parameter the method observes, beside the annotation that declares it
     * an observer. One on the method itself is read as well, since it is the more obvious place to write one and
     * says the same thing.</p>
     */
    private static int priorityOf(ParameterElement parameter, MethodElement method) {
        OptionalInt onParameter = parameter.getAnnotationMetadata().intValue(Cdi.PRIORITY, "value");
        if (onParameter.isPresent()) {
            return onParameter.getAsInt();
        }
        return method.getAnnotationMetadata().intValue(Cdi.PRIORITY, "value").orElse(DEFAULT_PRIORITY);
    }

    private static boolean validate(MethodElement method, VisitorContext context) {
        if (method.hasDeclaredAnnotation(Cdi.PRODUCES)) {
            context.fail("A method cannot be both a producer method and an observer method", method);
            return false;
        }
        if (method.hasDeclaredAnnotation("jakarta.inject.Inject")) {
            context.fail("A method cannot be both an initializer method and an observer method", method);
            return false;
        }
        for (ParameterElement parameter : method.getParameters()) {
            if (parameter.hasDeclaredAnnotation(Cdi.DISPOSES)) {
                context.fail("A method cannot be both a disposer method and an observer method", method);
                return false;
            }
        }
        return true;
    }
}
