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

import io.micronaut.cdi.internal.metadata.CdiApplicationScope;
import io.micronaut.cdi.internal.metadata.CdiRequestScope;
import io.micronaut.cdi.internal.metadata.CdiScope;
import io.micronaut.cdi.processor.Cdi;
import io.micronaut.cdi.processor.CdiScopes;
import io.micronaut.context.annotation.Prototype;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.AnnotationUtil;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import io.micronaut.inject.ast.AnnotationElement;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.Element;
import io.micronaut.inject.ast.ElementQuery;
import io.micronaut.inject.ast.MemberElement;
import io.micronaut.inject.visitor.TypeElementQuery;
import io.micronaut.inject.visitor.TypeElementVisitor;
import io.micronaut.inject.visitor.VisitorContext;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Decides which scope a class is in, the way section 2.3.1 says it is decided, and records it.
 *
 * <p>The mappers read a scope as it is declared, which covers the ordinary case and misses the ones that turn on
 * the class hierarchy. A scope is inherited from the nearest superclass that declares one, but only if the scope
 * annotation itself is marked {@code Inherited} — and a superclass in between that declares any scope of its own
 * blocks what is above it, whether or not what it declares is inheritable. A stereotype's default comes last: it
 * applies only where nothing was declared or inherited.</p>
 *
 * <p>What is decided here is recorded twice over: the Micronaut scope the bean is held in, and the annotation
 * the author wrote, which is what the container reports. The dependent pseudo-scope is also taken off once it
 * has been read, since it is a scope in Micronaut's sense too and would otherwise be resolved in a context that
 * does not exist.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class CdiScopeVisitor implements TypeElementVisitor<Object, Object> {

    private static final String PSEUDO_SCOPE = "jakarta.inject.Scope";
    private static final String SCOPED_PROXY = "io.micronaut.runtime.context.scope.ScopedProxy";

    @Override
    public TypeElementQuery query() {
        return TypeElementQuery.onlyClass();
    }

    @Override
    public VisitorKind getVisitorKind() {
        return VisitorKind.ISOLATING;
    }

    /**
     * Runs after an extension has enhanced a class, and before anything that reads what scope it is in.
     *
     * @return The order
     */
    @Override
    public int getOrder() {
        return LOWEST_PRECEDENCE - 100;
    }

    @Override
    public void visitClass(ClassElement element, VisitorContext context) {
        readClass(element, context);
        for (MemberElement member : element.getEnclosedElements(
            ElementQuery.of(MemberElement.class).onlyDeclared())) {
            if (SpecificationBeans.isProducer(member)
                && !checkScopesOf(member, "The producer " + element.getName() + "#" + member.getName(),
                    context)) {
                // reported; the scopes — and what the mappers read them into — are taken off, so that the
                // proxy writer does not report the same mistake in different words
                for (String name : member.getDeclaredAnnotationNames()) {
                    if (isWrittenScope(name, context)) {
                        member.removeAnnotation(name);
                    }
                }
                member.removeAnnotation(CdiScope.class);
                member.removeAnnotation(CdiApplicationScope.class);
                member.removeAnnotation(CdiRequestScope.class);
                member.removeAnnotation(SCOPED_PROXY);
            }
        }
        element.getEnclosedElements(ElementQuery.ALL_METHODS).forEach(CdiScopeVisitor::readMember);
        element.getEnclosedElements(ElementQuery.ALL_FIELDS).forEach(CdiScopeVisitor::readMember);
    }

    /**
     * Whether the element declares at most one scope, which is what section 2.3.2 allows.
     *
     * @param element The element
     * @param described What to call it in the message
     * @param context The visitor context
     * @return Whether it is well formed
     */
    static boolean checkScopesOf(Element element, String described,
                                 VisitorContext context) {
        List<String> declared = new ArrayList<>();
        for (String name : element.getDeclaredAnnotationNames()) {
            if (isWrittenScope(name, context)) {
                declared.add(name);
            }
        }
        if (declared.size() > 1) {
            context.fail(described + " declares more than one scope " + declared
                + ", and a bean has at most one (section 2.3.2)", element);
            return false;
        }
        return true;
    }

    /**
     * Whether the annotation is a scope an application writes: one of the specification's, or one meta-annotated
     * {@code Scope} or {@code NormalScope} that the application declares. What Micronaut's mappers add beside a scope
     * - its own scopes, the markers of this module - is not one the bean declared.
     */
    private static boolean isWrittenScope(String name, VisitorContext context) {
        return Cdi.isScope(name) || (!name.startsWith("io.micronaut.") && isDeclaredScopeAnnotation(name, context));
    }

    /**
     * Whether the annotation is a scope of the specification's kind: one meta-annotated {@code Scope} or
     * {@code NormalScope}, which an application may declare as well as the container.
     */
    private static boolean isDeclaredScopeAnnotation(String name, VisitorContext context) {
        return context.getClassElement(name)
            .map(declaration -> declaration.hasDeclaredAnnotation("jakarta.inject.Scope")
                || declaration.hasDeclaredAnnotation(Cdi.NORMAL_SCOPE))
            .orElse(false);
    }

    /**
     * Whether a stereotype is well formed: section 2.7.1 lets it carry at most one scope, and its
     * {@code Named} — if it declares one — must have no value of its own.
     */
    private static boolean checkStereotype(ClassElement element, VisitorContext context) {
        if (!checkScopesOf(element, "The stereotype " + element.getName(), context)) {
            return false;
        }
        String named = namedValueOf(element);
        if (named != null) {
            context.fail("The stereotype " + element.getName() + " declares a Named with the value \""
                + named + "\"; a stereotype's Named names nothing itself (section 2.7.1)", element);
            return false;
        }
        return true;
    }

    /**
     * The value a stereotype's {@code Named} names, which it may not (section 2.7.1): the bean the stereotype is
     * on takes the default name. Asked as the stereotype compiles, and again where a class uses one compiled
     * elsewhere.
     *
     * @param stereotype The stereotype
     * @return The value, or {@code null} where the stereotype declares no Named or one without a value
     */
    private static @Nullable String namedValueOf(ClassElement stereotype) {
        return stereotype.getAnnotationMetadata().stringValue("jakarta.inject.Named")
            .filter(name -> !name.isEmpty())
            .orElse(null);
    }

    /**
     * Whether the stereotypes a class carries agree: two that name different default scopes are a conflict
     * unless the class settles it by declaring a scope of its own (section 2.7.1), and two that name
     * different priorities are a conflict outright.
     */
    private static boolean checkStereotypesOf(ClassElement element, VisitorContext context) {
        List<String> stereotypes = element.getAnnotationMetadata()
            .getAnnotationNamesByStereotype(Cdi.STEREOTYPE);
        if (stereotypes.isEmpty()) {
            return true;
        }
        Set<String> scopes = new LinkedHashSet<>();
        Set<Integer> priorities = new LinkedHashSet<>();
        for (String stereotype : stereotypes) {
            ClassElement declaration = context.getClassElement(stereotype).orElse(null);
            if (declaration == null) {
                continue;
            }
            for (String name : declaration.getDeclaredAnnotationNames()) {
                if (Cdi.isScope(name)) {
                    scopes.add(name);
                }
            }
            declaration.getAnnotationMetadata()
                .intValue(Cdi.PRIORITY, AnnotationMetadata.VALUE_MEMBER)
                .ifPresent(priorities::add);
            // a value written on the stereotype is a definition error wherever it is used, the stereotype
            // perhaps compiled elsewhere
            String named = namedValueOf(declaration);
            if (named != null) {
                context.fail("The stereotype " + stereotype + " on " + element.getName() + " declares a "
                    + "Named with the value \"" + named + "\"; a stereotype's Named names nothing itself "
                    + "(section 2.7.1)", element);
                return false;
            }
        }
        // any scope settles it, including one the application declared: the specification says "a scope",
        // not one of the built-in four
        boolean declaresItsOwnScope = element.getDeclaredAnnotationNames().stream()
            .anyMatch(name -> Cdi.isScope(name) || isDeclaredScopeAnnotation(name, context));
        if (scopes.size() > 1 && stereotypes.size() == 1) {
            // one stereotype naming two scopes is malformed in itself; the bean cannot settle that
            context.fail("The stereotype " + stereotypes.get(0) + " on " + element.getName() + " names more "
                + "than one scope " + scopes + ", and a stereotype names at most one (section 2.7.1)",
                element);
            return false;
        }
        if (scopes.size() > 1 && !declaresItsOwnScope) {
            context.fail("The class " + element.getName() + " carries stereotypes naming different default "
                + "scopes " + scopes + " and declares no scope of its own to settle it (section 2.7.1)",
                element);
            return false;
        }
        boolean declaresItsOwnPriority = element.getDeclaredAnnotationNames().contains(Cdi.PRIORITY);
        if (priorities.size() > 1 && !declaresItsOwnPriority) {
            context.fail("The class " + element.getName() + " carries stereotypes naming different "
                + "priorities " + priorities + " and declares no priority of its own to settle it "
                + "(section 2.7.1)", element);
            return false;
        }
        return true;
    }

    private static void readClass(ClassElement element, VisitorContext context) {

        if (!checkScopesOf(element, element.getName(), context)) {
            return;
        }
        if (element.hasDeclaredAnnotation(Cdi.STEREOTYPE) && !checkStereotype(element, context)) {
            return;
        }
        if (!checkStereotypesOf(element, context)) {
            return;
        }
        if (element.hasDeclaredAnnotation(CdiScope.class)) {
            // a mapper has already read what the class declares
            takeOffTheDependentPseudoScope(element);
            if (!element.booleanValue(CdiScope.class, "normal").orElse(false)) {
                // a pseudo-scope the class declares wins over a normal scope it would inherit or a stereotype it
                // declares carries, and what the mappers read that one into goes with it
                takeOffTheNormalScopeItOverrides(element);
            }
            return;
        }
        String resolved = resolveScope(element, context);
        takeOffTheDependentPseudoScope(element);
        if (resolved == null) {
            return;
        }
        // what the hierarchy would hand down is replaced by what was resolved, so that what the bean is held in
        // agrees with what the container reports for it
        element.removeAnnotation(CdiScope.class);
        element.removeAnnotation(CdiApplicationScope.class);
        element.removeAnnotation(CdiRequestScope.class);
        // a scope other than the specification's own is resolved here only where it is a scope of the kind the
        // specification declares, and whether it is normal is what its declaration says
        boolean normal = isNormal(resolved, context);
        if (writeScopeServedByItsOwnContext(element, resolved, normal)) {
            return;
        }
        for (AnnotationValue<?> annotation
            : CdiScopes.annotationsOf(resolved, normal)) {
            element.annotate(annotation);
        }
    }

    /**
     * Writes a normal scope other than the specification's own onto a class or a producer that declares or
     * inherits it, directly or through a stereotype: the scope's own annotation as the first Micronaut scope of
     * the bean, and the application scope the mappers read it into behind it.
     *
     * <p>What the mappers wrote is taken off first, wherever it came from - written on the element, or carried
     * by an annotation it declares or inherits - so that the scope's own annotation leads the scopes Micronaut
     * tries, and the proxying is written again with it.</p>
     *
     * @param element The class or the producer
     * @param scope   The name of the scope annotation
     * @param normal  Whether it is a normal scope
     * @return Whether the scope is one of that kind, and was written
     */
    static boolean writeScopeServedByItsOwnContext(Element element, String scope, boolean normal) {
        if (!CdiScopes.isServedByItsOwnContext(scope, normal)) {
            return false;
        }
        // the proxying first: what the application scope carries is listed under it, and goes with it
        element.removeStereotype(SCOPED_PROXY);
        element.removeAnnotation(CdiApplicationScope.class);
        element.removeStereotype(CdiApplicationScope.class);
        for (AnnotationValue<?> annotation : CdiScopes.annotationsOf(scope, true)) {
            element.annotate(annotation);
        }
        return true;
    }

    /**
     * Takes off what the mappers read a normal scope into, where the class or the producer is in a pseudo-scope
     * that wins over it: a normal scope the class inherits from a superclass when it declares the dependent or the
     * singleton pseudo-scope itself (section 2.3.1), or one a stereotype carries when the class or the producer
     * declares a pseudo-scope beside it (section 2.7.2).
     *
     * <p>What goes is the application and the request scope this module serves normal scopes with, and the
     * client proxy they carry: the {@code ScopedProxy} stereotype, and the lazy {@code Around} advice with a
     * separate target it brings. A lazy proxy that keeps no target would hand each call to a new dependent
     * instance. Advice of another origin - an interceptor binding - is kept.</p>
     *
     * <p>A class is read with what it inherits, which is where such a scope comes from; a producer with only what
     * it declares itself, since the metadata of a member carries what the class declaring it declares as well,
     * and the scope of that class is not the produced bean's.</p>
     *
     * @param element The class or the producer
     */
    static void takeOffTheNormalScopeItOverrides(Element element) {
        AnnotationMetadata read = element instanceof ClassElement
            ? element.getAnnotationMetadata() : element.getAnnotationMetadata().getDeclaredMetadata();
        if (!read.hasStereotype(SCOPED_PROXY)) {
            return;
        }
        // the advice the proxying alone brings goes with it; an Around written on the element itself or carried
        // by anything else is advice of its own
        boolean proxyingAdviceOnly = !read.hasDeclaredAnnotation(AnnotationUtil.ANN_AROUND)
            && read.getAnnotationNamesByStereotype(AnnotationUtil.ANN_AROUND).stream().allMatch(SCOPED_PROXY::equals);
        // Micronaut proxies an element for an interceptor binding of the around kind as well, and the proxying
        // brings one of its own
        List<AnnotationValue<Annotation>> bindings =
            read.getAnnotationValuesByName(AnnotationUtil.ANN_INTERCEPTOR_BINDING);
        element.removeAnnotation(CdiApplicationScope.class);
        element.removeStereotype(CdiApplicationScope.class);
        element.removeAnnotation(CdiRequestScope.class);
        element.removeStereotype(CdiRequestScope.class);
        element.removeStereotype(SCOPED_PROXY);
        if (proxyingAdviceOnly) {
            element.removeAnnotation(AnnotationUtil.ANN_AROUND);
            element.removeStereotype(AnnotationUtil.ANN_AROUND);
        }
        if (bindings.stream().anyMatch(CdiScopeVisitor::isTheProxying)) {
            // the bindings are held together, so all go and those of another origin are written back
            element.removeAnnotation(AnnotationUtil.ANN_INTERCEPTOR_BINDINGS);
            element.removeStereotype(AnnotationUtil.ANN_INTERCEPTOR_BINDINGS);
            for (AnnotationValue<Annotation> binding : bindings) {
                if (!isTheProxying(binding)) {
                    element.annotate(binding);
                }
            }
        }
    }

    /**
     * Whether the interceptor binding is the one the {@code ScopedProxy} stereotype brings.
     */
    private static boolean isTheProxying(AnnotationValue<Annotation> binding) {
        return List.of(binding.stringValues()).contains(SCOPED_PROXY);
    }

    /**
     * The scope of the class, resolved the way section 2.3.1 resolves it.
     *
     * @return The name of the scope annotation, or {@code null} where the class has none and is not a bean of
     * the specification at all
     */
    private static @Nullable String resolveScope(ClassElement element, VisitorContext context) {
        // what the class declares, directly or through a stereotype it declares, wins outright
        DeclaredScope declared = scopeDeclaredOn(element, context);
        if (declared != null) {
            return declared.scope();
        }
        // then the nearest superclass that declares a scope decides: what it declares is inherited if the
        // annotation the superclass actually wrote — the scope itself, or the stereotype that carries it — says
        // it is, and blocks everything above it either way
        ClassElement superType = element.getSuperType().orElse(null);
        while (superType != null && !Object.class.getName().equals(superType.getName())) {
            DeclaredScope inherited = scopeDeclaredOn(superType, context);
            if (inherited != null) {
                return isInherited(inherited.contributedBy(), context)
                    ? inherited.scope() : stereotypeDefault(element);
            }
            superType = superType.getSuperType().orElse(null);
        }
        return stereotypeDefault(element);
    }

    /**
     * The record of the scope a producer method or field declares, directly or through a stereotype it declares,
     * resolved as the scope of a class is: a normal scope - one of the specification's own, or one the application
     * or an extension declared - the dependent pseudo-scope, or another pseudo-scope. A scope written on the producer
     * itself wins over one a stereotype it declares carries (section 2.7.2). What the class that declares the
     * producer declares is not the producer's.
     *
     * @param member  The producer
     * @param context The visitor context
     * @return The record, or {@code null} where the producer declares no scope
     */
    public static @Nullable AnnotationValue<CdiScope> scopeRecordOf(Element member,
                                                                                               VisitorContext context) {
        for (String name : member.getDeclaredAnnotationNames()) {
            // the session and the conversation scope belong to CDI Full, and are not read as scopes here
            if (isWrittenScope(name, context) && !Cdi.SESSION_SCOPED.equals(name)
                && !Cdi.CONVERSATION_SCOPED.equals(name)) {
                return CdiScopes.recordOf(name, isNormal(name, context));
            }
        }
        DeclaredScope declared = scopeDeclaredOn(member, context);
        String scope = declared != null ? declared.scope() : pseudoScopeDeclaredOn(member, context);
        return scope == null ? null : CdiScopes.recordOf(scope, isNormal(scope, context));
    }

    /**
     * Whether a resolved scope is a normal one: the specification's application and request scopes are, the
     * dependent and singleton pseudo-scopes are not, and any other is what its declaration says.
     */
    private static boolean isNormal(String scope, VisitorContext context) {
        if (Cdi.DEPENDENT.equals(scope) || "jakarta.inject.Singleton".equals(scope)) {
            return false;
        }
        if (Cdi.APPLICATION_SCOPED.equals(scope) || Cdi.REQUEST_SCOPED.equals(scope)) {
            return true;
        }
        // a scope is resolved only where the compilation can read its declaration
        return context.getClassElement(scope)
            .map(declaration -> declaration.hasDeclaredAnnotation(Cdi.NORMAL_SCOPE)).orElse(false);
    }

    /**
     * The pseudo-scope other than the dependent one the element declares, directly or through a stereotype it
     * declares: the singleton pseudo-scope, or an annotation the application declared a scope.
     */
    private static @Nullable String pseudoScopeDeclaredOn(Element element, VisitorContext context) {
        for (String candidate : element.getAnnotationMetadata().getAnnotationNamesByStereotype(PSEUDO_SCOPE)) {
            String scope = resolveToPseudoScope(candidate, context, new HashSet<>());
            if (scope != null && !Cdi.DEPENDENT.equals(scope)
                && declaredContributorOf(element, candidate, context) != null) {
                return scope;
            }
        }
        return null;
    }

    /**
     * The pseudo-scope the candidate annotation is or carries, as {@link #resolveToScope} finds a normal scope;
     * Micronaut's own scopes, which the mappers add, are none of the application's.
     */
    private static @Nullable String resolveToPseudoScope(String candidate, VisitorContext context,
                                                         Set<String> seen) {
        if ("jakarta.inject.Singleton".equals(candidate) || Cdi.DEPENDENT.equals(candidate)) {
            return candidate;
        }
        if (candidate.startsWith("io.micronaut.") || !seen.add(candidate)) {
            return null;
        }
        ClassElement annotation = context.getClassElement(candidate).orElse(null);
        if (annotation == null) {
            return null;
        }
        if (annotation.hasDeclaredAnnotation(PSEUDO_SCOPE)) {
            return candidate;
        }
        for (String carried : annotation.getAnnotationMetadata().getAnnotationNamesByStereotype(PSEUDO_SCOPE)) {
            String resolved = resolveToPseudoScope(carried, context, seen);
            if (resolved != null) {
                return resolved;
            }
        }
        return null;
    }

    /**
     * The scope the class itself declares: one of the specification's own or an annotation the application
     * declared a normal scope, written on the class directly or carried by a stereotype the class declares.
     */
    private static @Nullable DeclaredScope scopeDeclaredOn(Element element, VisitorContext context) {
        for (String candidate : element.getAnnotationMetadata().getAnnotationNamesByStereotype(Cdi.NORMAL_SCOPE)) {
            // what the index hands back is the annotation credited with the stereotype, which for a transitive
            // chain is the annotation the class declares — a stereotype, as often as not — rather than the
            // scope itself. What was written decides inheritance; what it carries is the scope
            String scope = resolveToScope(candidate, context);
            if (scope == null || Cdi.SESSION_SCOPED.equals(scope) || Cdi.CONVERSATION_SCOPED.equals(scope)) {
                continue;
            }
            String contributor = declaredContributorOf(element, candidate, context);
            if (contributor != null) {
                return new DeclaredScope(scope, contributor);
            }
        }
        if (element.getAnnotationMetadata().hasStereotype(Cdi.DEPENDENT)) {
            String contributor = declaredContributorOf(element, Cdi.DEPENDENT, context);
            if (contributor != null) {
                return new DeclaredScope(Cdi.DEPENDENT, contributor);
            }
        }
        return null;
    }

    /**
     * The scope the candidate annotation is or carries: the candidate itself where it is declared a normal
     * scope, and otherwise the scope found through its own meta-annotations — a stereotype carries its scope at
     * one remove, and a stereotype declared on a stereotype at two.
     *
     * <p>An annotation is read as the element the compilation has of it, whether it is compiled in the same
     * round or is already compiled; the scopes of the specification are known by name.</p>
     */
    private static @Nullable String resolveToScope(String candidate, VisitorContext context) {
        if (Cdi.APPLICATION_SCOPED.equals(candidate) || Cdi.REQUEST_SCOPED.equals(candidate)
            || Cdi.SESSION_SCOPED.equals(candidate) || Cdi.CONVERSATION_SCOPED.equals(candidate)) {
            return candidate;
        }
        ClassElement annotation = context.getClassElement(candidate).orElse(null);
        if (annotation != null) {
            if (annotation.hasDeclaredAnnotation(Cdi.NORMAL_SCOPE)) {
                return candidate;
            }
            for (String carried : annotation.getAnnotationMetadata()
                .getAnnotationNamesByStereotype(Cdi.NORMAL_SCOPE)) {
                if (!carried.equals(candidate)) {
                    String resolved = resolveToScope(carried, context);
                    if (resolved != null) {
                        return resolved;
                    }
                }
            }
            return null;
        }
        // an annotation the compilation cannot see names no scope this build can read
        return null;
    }

    /**
     * The annotation the class wrote that brought it the given one: the annotation declared outright, or a
     * declared annotation that carries it. An annotation that reached the class some other way — by
     * inheritance — has no declared contributor, and none is returned for it.
     */
    private static @Nullable String declaredContributorOf(Element element,
                                                          String annotation,
                                                          VisitorContext context) {
        if (element.hasDeclaredAnnotation(annotation)) {
            return annotation;
        }
        for (String declared : element.getDeclaredAnnotationNames()) {
            ClassElement declaredAnnotation = context.getClassElement(declared).orElse(null);
            if (declaredAnnotation != null
                && (declaredAnnotation.hasDeclaredAnnotation(annotation)
                || declaredAnnotation.getAnnotationMetadata().hasStereotype(annotation))) {
                return declared;
            }
        }
        return null;
    }

    /**
     * The dependent pseudo-scope a stereotype leaves a bean in when nothing was declared or inherited, or
     * nothing at all where the class has no stereotype either.
     */
    private static @Nullable String stereotypeDefault(ClassElement element) {
        return element.getAnnotationMetadata().hasStereotype(Cdi.STEREOTYPE) ? Cdi.DEPENDENT : null;
    }

    /**
     * Whether the scope annotation is marked {@code Inherited}, which is what lets a subclass inherit it.
     *
     * <p>An annotation the compilation can see answers for itself; one it cannot — which should not happen for
     * a scope a bean declares — is not inherited.</p>
     */
    private static boolean isInherited(String scope, VisitorContext context) {
        if (context.getClassElement(scope).orElse(null) instanceof AnnotationElement annotation) {
            return annotation.isInherited();
        }
        return false;
    }

    /**
     * A member is read the way the mappers read it: the scope it declares, with nothing inherited to weigh.
     */
    private static void readMember(Element element) {
        if (!element.hasAnnotation(CdiScope.class) && element.hasAnnotation(Cdi.DEPENDENT)) {
            element.annotate(Prototype.class);
            element.annotate(CdiScope.class, builder -> builder.value(Cdi.DEPENDENT));
        }
        takeOffTheDependentPseudoScope(element);
    }

    /**
     * Takes the dependent pseudo-scope off an element once it has been read, since it is a Micronaut scope in
     * its own right and would otherwise be resolved in a context that does not exist.
     */
    private static void takeOffTheDependentPseudoScope(Element element) {
        if (element.hasAnnotation(Cdi.DEPENDENT)) {
            element.removeAnnotation(Cdi.DEPENDENT);
        }
    }

    /**
     * A scope a class declares, and the annotation it wrote to declare it: the scope itself, or the stereotype
     * that carries it. Which one it was matters to inheritance, since {@code Inherited} is read off what was
     * written rather than off what it implies.
     *
     * @param scope         The name of the scope annotation
     * @param contributedBy The name of the annotation the class wrote
     */
    private record DeclaredScope(String scope, String contributedBy) {
    }
}
