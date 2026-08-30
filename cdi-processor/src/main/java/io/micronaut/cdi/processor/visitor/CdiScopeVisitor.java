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

import io.micronaut.cdi.annotation.CdiApplicationScope;
import io.micronaut.cdi.annotation.CdiRequestScope;
import io.micronaut.cdi.annotation.CdiScope;
import io.micronaut.cdi.processor.Cdi;
import io.micronaut.context.annotation.Prototype;
import io.micronaut.core.annotation.Internal;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.Element;
import io.micronaut.inject.ast.ElementQuery;
import io.micronaut.inject.visitor.TypeElementVisitor;
import io.micronaut.inject.visitor.VisitorContext;
import org.jspecify.annotations.Nullable;

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
        element.getEnclosedElements(ElementQuery.ALL_METHODS).forEach(CdiScopeVisitor::readMember);
        element.getEnclosedElements(ElementQuery.ALL_FIELDS).forEach(CdiScopeVisitor::readMember);
    }

    private static void readClass(ClassElement element, VisitorContext context) {
        if (element.hasDeclaredAnnotation(CdiScope.class)) {
            // a mapper has already read what the class declares
            takeOffTheDependentPseudoScope(element);
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
        switch (resolved) {
            case Cdi.APPLICATION_SCOPED -> {
                element.annotate(CdiApplicationScope.class);
                element.annotate(CdiScope.class, builder -> builder
                    .value(Cdi.APPLICATION_SCOPED).member("normal", true));
            }
            case Cdi.REQUEST_SCOPED -> {
                element.annotate(CdiRequestScope.class);
                element.annotate(CdiScope.class, builder -> builder
                    .value(Cdi.REQUEST_SCOPED).member("normal", true));
            }
            case Cdi.DEPENDENT -> {
                element.annotate(Prototype.class);
                element.annotate(CdiScope.class, builder -> builder.value(Cdi.DEPENDENT));
            }
            default -> element.annotate(CdiScope.class, builder -> builder
                .value(resolved).member("normal", true));
        }
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
     * The scope the class itself declares: one of the specification's own or an annotation the application
     * declared a normal scope, written on the class directly or carried by a stereotype the class declares.
     */
    private static @Nullable DeclaredScope scopeDeclaredOn(ClassElement element, VisitorContext context) {
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
     * <p>An annotation compiled in the same round is read as an element; one that is already compiled — the
     * scopes of the specification among them — is read off the compiler's classpath.</p>
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
        try {
            Class<?> type = Class.forName(candidate, false, CdiScopeVisitor.class.getClassLoader());
            if (type.isAnnotationPresent(jakarta.enterprise.context.NormalScope.class)) {
                return candidate;
            }
            for (java.lang.annotation.Annotation meta : type.getAnnotations()) {
                String name = meta.annotationType().getName();
                if (!name.startsWith("java.lang.annotation.") && !name.equals(candidate)) {
                    String resolved = resolveToScope(name, context);
                    if (resolved != null) {
                        return resolved;
                    }
                }
            }
        } catch (ClassNotFoundException | LinkageError e) {
            // not on the compiler's classpath either; it names no scope this build can see
        }
        return null;
    }

    /**
     * The annotation the class wrote that brought it the given one: the annotation declared outright, or a
     * declared annotation that carries it. An annotation that reached the class some other way — by
     * inheritance — has no declared contributor, and none is returned for it.
     */
    private static @Nullable String declaredContributorOf(ClassElement element,
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
     * <p>An annotation compiled in the same round is read as an element; one that is already compiled — the
     * scopes of the specification itself, for instance — is read off the compiler's classpath, which is where
     * an annotation processor's own dependencies live.</p>
     */
    private static boolean isInherited(String scope, VisitorContext context) {
        ClassElement annotation = context.getClassElement(scope).orElse(null);
        if (annotation != null) {
            Boolean fromSource = inheritedOnTheSourceElement(annotation);
            if (fromSource != null) {
                return fromSource;
            }
        }
        try {
            return Class.forName(scope, false, CdiScopeVisitor.class.getClassLoader())
                .isAnnotationPresent(java.lang.annotation.Inherited.class);
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    /**
     * Whether the annotation the element describes is marked {@code Inherited}, read off the compiler's own
     * element rather than off Micronaut's metadata: Micronaut leaves the {@code java.lang.annotation}
     * meta-annotations out of what it records, so the marker is only visible where the compiler put it.
     *
     * @return Whether it is marked, or {@code null} when the native element cannot be reached
     */
    private static @Nullable Boolean inheritedOnTheSourceElement(ClassElement annotation) {
        Object nativeType = annotation.getNativeType();
        javax.lang.model.element.Element source = unwrap(nativeType);
        if (source == null) {
            return null;
        }
        for (javax.lang.model.element.AnnotationMirror mirror : source.getAnnotationMirrors()) {
            if (mirror.getAnnotationType().toString().equals("java.lang.annotation.Inherited")) {
                return true;
            }
        }
        return false;
    }

    /**
     * The compiler's element inside whatever Micronaut wrapped it in, which differs between Micronaut
     * versions: the element itself, or a holder with an {@code element()} accessor.
     */
    private static javax.lang.model.element.@Nullable Element unwrap(Object nativeType) {
        if (nativeType instanceof javax.lang.model.element.Element element) {
            return element;
        }
        try {
            Object unwrapped = nativeType.getClass().getMethod("element").invoke(nativeType);
            if (unwrapped instanceof javax.lang.model.element.Element element) {
                return element;
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            // not a holder this build knows
        }
        return null;
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
