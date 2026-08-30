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

import io.micronaut.core.annotation.Internal;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.ConstructorElement;
import io.micronaut.inject.ast.ElementQuery;
import io.micronaut.inject.ast.MethodElement;
import io.micronaut.inject.visitor.TypeElementVisitor;
import io.micronaut.inject.visitor.VisitorContext;

/**
 * The unproxyable bean types of section 3.11, detected as the class compiles: a bean in a normal scope, and a
 * bean whose methods are intercepted, is reached through a proxy, and a class the proxy cannot extend is a
 * deployment problem — which is what a container reports as it deploys, and deployment is compilation here.
 *
 * <p>Micronaut's own writer rejects a final class and a public final method with advice; what it does not
 * reject — a missing or private no-parameter constructor, and a final method of any other visibility — is
 * rejected here, because a proxy of the specification extends the class through that constructor and overrides
 * those methods.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class ProxyabilityVisitor implements TypeElementVisitor<Object, Object> {

    @Override
    public VisitorKind getVisitorKind() {
        return VisitorKind.ISOLATING;
    }

    @Override
    public int getOrder() {
        return LOWEST_PRECEDENCE - 140;
    }

    @Override
    public void visitClass(ClassElement element, VisitorContext context) {
        if (element.isAbstract() || element.isInterface() || element.isEnum()) {
            return;
        }
        if (element.hasDeclaredAnnotation("jakarta.interceptor.Interceptor")) {
            // an interceptor class is invoked, not proxied
            return;
        }
        boolean normalScoped = isNormalScoped(element);
        boolean intercepted = element.hasStereotype("jakarta.interceptor.InterceptorBinding");
        if (!normalScoped && !(intercepted && isBeanOfTheSpecification(element))) {
            return;
        }
        String reached = normalScoped ? "its normal scope" : "the interception of its methods";
        if (normalScoped) {
            for (io.micronaut.inject.ast.FieldElement field
                : element.getEnclosedElements(ElementQuery.ALL_FIELDS)) {
                if (field.isPublic() && !field.isStatic()) {
                    // section 2.7: a bean in a normal scope is reached only through its proxy, and a public
                    // field would be read past it — a definition error, unlike the unproxyable types below
                    context.fail("The bean " + element.getName() + " declares the public field "
                        + field.getName() + " and a normal scope: a public field is read without the proxy "
                        + "a normal scope requires (section 2.7)", field);
                    return;
                }
            }
        }
        String unproxyable = whyUnproxyable(element, normalScoped);
        if (unproxyable == null) {
            return;
        }
        if (!normalScoped) {
            // an intercepted bean that cannot be proxied cannot be intercepted at all, which is the
            // deployment problem the message names
            context.fail("The bean " + element.getName() + " cannot be proxied for " + reached
                + ": " + unproxyable + " (section 3.11)", element);
            return;
        }
        // section 3.11 has an unproxyable bean in a normal scope deploy anyway: it fails only when a
        // contextual reference is asked for — or, at an injection point, as a deployment problem the
        // deployment validation reports. The proxy Micronaut would have written cannot exist, so the
        // annotations that would have had it written are taken off, and why is remembered instead
        String reason = "The bean " + element.getName() + " cannot be proxied for its normal scope: "
            + unproxyable + " (section 3.11)";
        // the proxy Micronaut would have written cannot exist, so everything that would have had it written —
        // the scope annotations, which carry the proxying — is taken off, and why is remembered instead. The
        // bean stays a bean: it deploys, and every attempt at a contextual reference reads the reason and
        // throws the UnproxyableResolutionException of section 3.11
        for (String proxying : element.getAnnotationMetadata()
            .getAnnotationNamesByStereotype("io.micronaut.runtime.context.scope.ScopedProxy")) {
            element.removeAnnotation(proxying);
        }
        element.removeAnnotation("jakarta.enterprise.context.RequestScoped");
        element.removeAnnotation("jakarta.enterprise.context.ApplicationScoped");
        element.removeAnnotation("io.micronaut.cdi.annotation.CdiRequestScope");
        element.removeAnnotation("io.micronaut.cdi.annotation.CdiApplicationScope");
        element.annotate("io.micronaut.context.annotation.Prototype");
        element.annotate("io.micronaut.cdi.annotation.CdiUnproxyable", builder -> builder.value(reason));
    }

    /**
     * What of section 3.11 the class lacks, or {@code null} when a proxy can extend it.
     */
    private static @io.micronaut.core.annotation.Nullable String whyUnproxyable(ClassElement element,
                                                                                boolean normalScoped) {
        if (element.isFinal()) {
            return "the class is final and a proxy extends it";
        }
        ConstructorElement noArguments = null;
        boolean injectConstructor = false;
        for (ConstructorElement constructor : element.getEnclosedElements(ElementQuery.CONSTRUCTORS)) {
            if (constructor.getParameters().length == 0) {
                noArguments = constructor;
            }
            if (constructor.hasDeclaredAnnotation("jakarta.inject.Inject")
                || constructor.hasDeclaredAnnotation(io.micronaut.core.annotation.AnnotationUtil.INJECT)) {
                injectConstructor = true;
            }
        }
        boolean reachableNoArguments = noArguments != null && !noArguments.isPrivate();
        if (normalScoped && !reachableNoArguments) {
            // the client proxy of section 3.11 extends the class through the constructor without parameters
            return "a proxy extends the class through a non-private constructor without parameters, "
                + "and there is none";
        }
        if (!normalScoped && !reachableNoArguments && !injectConstructor) {
            // an interception proxy is created through the bean constructor, and this class has none a bean
            // could be created through
            return "a proxy extends the class through a non-private constructor without parameters or the "
                + "bean constructor, and there is neither";
        }
        for (MethodElement method : element.getEnclosedElements(ElementQuery.ALL_METHODS)) {
            if (method.isFinal() && !method.isPrivate() && !method.isStatic()) {
                return "the method " + method.getName() + " is final and a proxy overrides every method";
            }
        }
        return null;
    }

    private static boolean isNormalScoped(ClassElement element) {
        if (element.hasStereotype("jakarta.enterprise.context.NormalScope")) {
            return true;
        }
        return element.getAnnotationMetadata()
            .booleanValue("io.micronaut.cdi.annotation.CdiScope", "normal")
            .orElse(false);
    }

    private static boolean isBeanOfTheSpecification(ClassElement element) {
        return element.hasStereotype("io.micronaut.cdi.annotation.CdiScope");
    }
}
