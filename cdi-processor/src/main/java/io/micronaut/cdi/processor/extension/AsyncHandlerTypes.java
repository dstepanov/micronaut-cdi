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
package io.micronaut.cdi.processor.extension;

import io.micronaut.core.annotation.Internal;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.MethodElement;
import io.micronaut.inject.ast.ParameterElement;
import io.micronaut.inject.visitor.VisitorContext;
import jakarta.enterprise.inject.spi.DefinitionException;
import jakarta.enterprise.inject.spi.DeploymentException;
import org.jspecify.annotations.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The async handlers of method invokers (CDI 5.0, section 10.5), read as the application compiles: where the
 * type arguments of a handler are known, and where an invoker is built for the method it is to handle.
 *
 * <p>A handler is a service provider of {@code AsyncHandler.ReturnType} or {@code AsyncHandler.ParameterType},
 * found the way the compiler finds a build compatible extension - registered in the service entries the processor
 * path holds - or named with the compiler option {@link #OPTION}. Its async type is the erasure of the type
 * argument to the one of the two it implements directly; implementing it indirectly or raw, with a type variable or
 * an array for an argument, and implementing both are definition errors; two handlers of one kind for one type are
 * a deployment problem.
 * The built-in return type handlers for {@code CompletionStage}, {@code CompletableFuture} and
 * {@code Flow.Publisher} are the container's.</p>
 *
 * <p>Nothing is held between the questions: each reads the service entries again, which a compilation asks for
 * once to validate the handlers and once per invoker built.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
final class AsyncHandlerTypes {

    /**
     * The compiler option naming async handler classes, separated by commas, beside the registered ones: what a
     * harness that compiles a deployment of its own uses for the deployment's service entries.
     */
    static final String OPTION = "micronaut.cdi.asyncHandlers";

    static final String RETURN_TYPE = "jakarta.enterprise.invoke.AsyncHandler.ReturnType";
    static final String PARAMETER_TYPE = "jakarta.enterprise.invoke.AsyncHandler.ParameterType";

    /**
     * The built-in return type async types.
     */
    static final Set<String> BUILT_IN = Set.of("java.util.concurrent.CompletionStage",
        "java.util.concurrent.CompletableFuture", "java.util.concurrent.Flow.Publisher");

    private AsyncHandlerTypes() {
    }

    /**
     * Every async handler of the compilation, validated.
     *
     * @param context The compilation
     * @return The handlers
     * @throws DefinitionException Where a handler is malformed
     * @throws DeploymentException Where two handlers of one kind handle one type
     */
    static List<Handler> of(VisitorContext context) {
        Set<String> names = new LinkedHashSet<>();
        String named = context.getOptions().get(OPTION);
        if (named != null) {
            for (String name : named.split(",")) {
                if (!name.isBlank()) {
                    names.add(name.trim());
                }
            }
        }
        ClassLoader loader = AsyncHandlerTypes.class.getClassLoader();
        names.addAll(registered(loader, "META-INF/services/jakarta.enterprise.invoke.AsyncHandler$ReturnType"));
        names.addAll(registered(loader, "META-INF/services/jakarta.enterprise.invoke.AsyncHandler$ParameterType"));
        List<Handler> handlers = new ArrayList<>(names.size());
        for (String name : names) {
            ClassElement handler = context.getClassElement(name)
                .or(() -> context.getClassElement(name.replace('$', '.')))
                .orElseThrow(() -> new DefinitionException("The async handler " + name + " is registered and is not "
                    + "on the classpath of the compilation"));
            Handler read = read(handler);
            for (Handler other : handlers) {
                if (other.returnType() == read.returnType() && other.asyncType().equals(read.asyncType())) {
                    throw new DeploymentException("The async handlers " + other.className() + " and "
                        + read.className() + " both handle the async type " + read.asyncType() + " (section 10.5)");
                }
            }
            handlers.add(read);
        }
        return handlers;
    }

    /**
     * Whether a handler exists for the async type: a built-in one or a registered one.
     *
     * @param asyncType The name of the async type
     * @param context   The compilation
     * @return Whether one exists
     */
    static boolean exists(String asyncType, VisitorContext context) {
        String type = asyncType.replace('$', '.');
        if (BUILT_IN.contains(type)) {
            return true;
        }
        for (Handler handler : of(context)) {
            if (handler.asyncType().equals(type)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The async handler a method matches, which is what makes it asynchronous: exactly one, or none.
     *
     * @param method  The target method of an invoker
     * @param context The compilation
     * @return The match, or {@code null} where the method is not asynchronous
     */
    static @Nullable Match match(MethodElement method, VisitorContext context) {
        List<Match> matches = new ArrayList<>(1);
        String returned = method.getReturnType().getName().replace('$', '.');
        if (!method.getReturnType().isArray() && BUILT_IN.contains(returned)) {
            matches.add(new Match(null, returned, -1));
        }
        ParameterElement[] parameters = method.getParameters();
        for (Handler handler : of(context)) {
            if (handler.returnType()) {
                if (!method.getReturnType().isArray() && handler.asyncType().equals(returned)) {
                    matches.add(new Match(handler.className(), handler.asyncType(), -1));
                }
                continue;
            }
            int found = -1;
            int count = 0;
            for (int i = 0; i < parameters.length; i++) {
                ClassElement type = parameters[i].getType();
                if (!type.isArray() && handler.asyncType().equals(type.getName().replace('$', '.'))) {
                    found = i;
                    count++;
                }
            }
            // a parameter type handler matches a method declaring exactly one parameter of its async type
            if (count == 1) {
                matches.add(new Match(handler.className(), handler.asyncType(), found));
            }
        }
        return matches.size() == 1 ? matches.get(0) : null;
    }

    private static Handler read(ClassElement handler) {
        ClassElement returnType = null;
        ClassElement parameterType = null;
        for (ClassElement implemented : handler.getInterfaces()) {
            String name = implemented.getName().replace('$', '.');
            if (RETURN_TYPE.equals(name)) {
                returnType = implemented;
            } else if (PARAMETER_TYPE.equals(name)) {
                parameterType = implemented;
            }
        }
        if (returnType != null && parameterType != null) {
            throw new DefinitionException("The async handler " + handler.getName() + " is both a return type and a "
                + "parameter type async handler (section 10.5)");
        }
        ClassElement direct = returnType != null ? returnType : parameterType;
        if (direct == null) {
            throw new DefinitionException("The async handler " + handler.getName() + " does not implement "
                + "AsyncHandler.ReturnType or AsyncHandler.ParameterType directly (section 10.5)");
        }
        Map<String, ClassElement> arguments = direct.getTypeArguments();
        ClassElement argument = arguments.size() == 1 ? arguments.values().iterator().next() : null;
        if (argument == null || direct.isRawType()) {
            throw new DefinitionException("The async handler " + handler.getName() + " implements "
                + direct.getName() + " raw (section 10.5)");
        }
        if (argument.isGenericPlaceholder() || argument.isWildcard()) {
            throw new DefinitionException("The async handler " + handler.getName() + " implements "
                + direct.getName() + " of a type variable (section 10.5)");
        }
        if (argument.isArray()) {
            throw new DefinitionException("The async handler " + handler.getName() + " implements "
                + direct.getName() + " of an array (section 10.5)");
        }
        return new Handler(handler.getName(), returnType != null, argument.getName().replace('$', '.'));
    }

    private static List<String> registered(ClassLoader loader, String resource) {
        List<String> names = new ArrayList<>();
        try {
            Enumeration<URL> files = loader.getResources(resource);
            while (files.hasMoreElements()) {
                try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(files.nextElement().openStream(), StandardCharsets.UTF_8))) {
                    for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                        int comment = line.indexOf('#');
                        String name = (comment < 0 ? line : line.substring(0, comment)).trim();
                        if (!name.isEmpty()) {
                            names.add(name);
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("The service entries " + resource + " could not be read", e);
        }
        return names;
    }

    /**
     * An async handler of the compilation.
     *
     * @param className  The handler class
     * @param returnType Whether it is a return type handler, rather than a parameter type one
     * @param asyncType  The erasure of its async type
     */
    record Handler(String className, boolean returnType, String asyncType) {
    }

    /**
     * The async handler a method matches.
     *
     * @param handlerClass The handler class, {@code null} for a built-in return type handler
     * @param asyncType    The async type
     * @param parameter    The position of the async parameter, -1 for a return type handler
     */
    record Match(@Nullable String handlerClass, String asyncType, int parameter) {
    }
}
