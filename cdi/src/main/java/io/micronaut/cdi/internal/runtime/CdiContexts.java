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
package io.micronaut.cdi.internal.runtime;

import io.micronaut.cdi.internal.context.ApplicationScope;
import io.micronaut.cdi.internal.context.RequestScope;
import io.micronaut.cdi.internal.extension.ExtensionContexts;
import io.micronaut.cdi.spi.CdiReflection;
import io.micronaut.context.BeanContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.context.NormalScope;
import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.context.spi.AlterableContext;
import jakarta.enterprise.context.spi.Context;
import jakarta.inject.Scope;
import jakarta.inject.Singleton;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The contexts of the container's scopes, which {@link CdiBeanContainer} answers for: the views of the built-in
 * scopes over the scopes that hold their instances, and the contexts extensions registered, which take the place of
 * a built-in one for its scope. What an extension registered is read from {@link ExtensionContexts}, which stays the
 * one place contexts are registered.
 *
 * @author Denis Stepanov
 * @since 1.0
 */
final class CdiContexts {

    private static final Set<String> NORMAL_SCOPES = Set.of(
        "jakarta.enterprise.context.ApplicationScoped", "jakarta.enterprise.context.RequestScoped",
        "jakarta.enterprise.context.SessionScoped", "jakarta.enterprise.context.ConversationScoped");
    private static final Set<String> PSEUDO_SCOPES = Set.of(
        "jakarta.enterprise.context.Dependent", "jakarta.inject.Singleton");

    private final BeanContext beanContext;
    /**
     * The contexts of the built-in scopes, which hold no state of their own beyond the scope they read: made once
     * rather than for each event an observer of a bean in one of them is notified of.
     */
    private final List<Context> requestContexts;
    private final List<Context> applicationContexts;
    private final List<Context> singletonContexts;
    private final List<Context> dependentContexts;
    /**
     * The contexts extensions registered, a bean of the context created as it starts: looked up once.
     */
    private volatile @Nullable Optional<ExtensionContexts> extensionContexts;

    CdiContexts(BeanContext beanContext, RequestScope requestScope, ApplicationScope applicationScope) {
        this.beanContext = beanContext;
        this.requestContexts = List.of(CdiContext.ofRequest(RequestScoped.class, requestScope));
        this.applicationContexts = List.of(CdiContext.ofApplication(ApplicationScoped.class, applicationScope));
        this.singletonContexts = List.of(CdiContext.ofSingleton(Singleton.class, beanContext));
        this.dependentContexts = List.of(CdiContext.holdingNothing(Dependent.class));
    }

    /**
     * Whether the annotation type is a scope: one of the specification, one an extension registered a context
     * for, or one the annotation class says is.
     *
     * @param annotationType The annotation type
     * @return Whether it is a scope
     */
    boolean isScope(Class<? extends Annotation> annotationType) {
        String name = annotationType.getName();
        if (NORMAL_SCOPES.contains(name) || PSEUDO_SCOPES.contains(name) || isRegisteredScope(annotationType)) {
            return true;
        }
        CdiReflection reflection = reflection("BeanContainer.isScope", annotationType);
        return reflection.isAnnotated(annotationType, Scope.class)
            || reflection.isAnnotated(annotationType, NormalScope.class);
    }

    /**
     * Whether the annotation type is a normal scope.
     *
     * @param annotationType The annotation type
     * @return Whether it is a normal scope
     */
    boolean isNormalScope(Class<? extends Annotation> annotationType) {
        String name = annotationType.getName();
        if (NORMAL_SCOPES.contains(name)) {
            return true;
        }
        if (PSEUDO_SCOPES.contains(name)) {
            return false;
        }
        Optional<ExtensionContexts> extensions = beanContext.findBean(ExtensionContexts.class);
        if (extensions.isPresent() && !extensions.get().contextsFor(annotationType).isEmpty()) {
            // a scope an extension registered is normal where the extension registered it as one
            return extensions.get().isNormal(annotationType);
        }
        return reflection("BeanContainer.isNormalScope", annotationType).isAnnotated(annotationType, NormalScope.class);
    }

    private boolean isRegisteredScope(Class<? extends Annotation> annotationType) {
        return beanContext.findBean(ExtensionContexts.class)
            .map(contexts -> !contexts.contextsFor(annotationType).isEmpty())
            .orElse(false);
    }

    /**
     * The active context of the scope, or {@code null} where none is active.
     *
     * @param scopeType The scope
     * @return The active context, or {@code null}
     * @throws IllegalArgumentException where no context of the scope is registered
     * @throws IllegalStateException    where more than one is active
     */
    @Nullable Context active(Class<? extends Annotation> scopeType) {
        Collection<Context> contexts = of(scopeType);
        if (contexts.isEmpty()) {
            throw new IllegalArgumentException("There is no context for the scope " + scopeType.getName());
        }
        return CdiContext.activeAmong(contexts, scopeType);
    }

    /**
     * The contexts of the scope: the ones an extension registered for it (section 2.10.1), which for the request or
     * the application scope take the place of the container's own, since the beans of the scope are held by them;
     * otherwise the container's own.
     *
     * @param scopeType The scope
     * @return The contexts, none for a scope no context is registered for
     */
    Collection<Context> of(Class<? extends Annotation> scopeType) {
        if (scopeType == Singleton.class) {
            return singletonContexts;
        }
        if (scopeType == Dependent.class) {
            return dependentContexts;
        }
        Optional<ExtensionContexts> extensions = extensionContexts;
        if (extensions == null) {
            extensions = beanContext.findBean(ExtensionContexts.class);
            extensionContexts = extensions;
        }
        if (extensions.isPresent()) {
            List<AlterableContext> registered = extensions.get().contextsFor(scopeType);
            if (!registered.isEmpty()) {
                return new ArrayList<>(registered);
            }
        }
        if (scopeType == RequestScoped.class) {
            return requestContexts;
        }
        if (scopeType == ApplicationScoped.class) {
            return applicationContexts;
        }
        return List.of();
    }

    /**
     * What answers a question about an annotation class the build recorded nothing of, which only reading the
     * class can answer.
     */
    private CdiReflection reflection(String what, Class<? extends Annotation> annotationType) {
        return CdiReflection.require(beanContext, what + " of " + annotationType.getName()
            + ", an annotation the application was not compiled with as one,");
    }
}
