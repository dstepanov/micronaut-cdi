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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The async handlers of method invokers (CDI 5.0, section 10.5), read as the application compiles: where the
 * type arguments of a handler are known, and where an invoker is built for the method it is to handle.
 *
 * <p>A handler is a service provider of {@code AsyncHandler.ReturnType} or {@code AsyncHandler.ParameterType},
 * registered in the service entries of the class path of the compilation (read through
 * {@link VisitorContext#getClasspathResources(String)}) or of the processor path, where the compiler finds a build
 * compatible extension, or named with the compiler option {@link #OPTION}: a handler the module being compiled
 * declares is on neither path yet, and is named with the option. Its async type is the erasure of the type
 * argument to the one of the two it implements directly; implementing it indirectly or raw, with a type variable or
 * an array for an argument, and implementing both are definition errors; two handlers of one async type, of either
 * kind or built in, are a deployment problem unless {@link #SELECTION_OPTION} selects one.
 * The built-in return type handlers for {@code CompletionStage}, {@code CompletableFuture} and
 * {@code Flow.Publisher} are the container's.</p>
 *
 * <p>The service entries are read once per compilation, as it starts the build compatible extension visitor,
 * which hands the handlers to what builds and validates invokers: each read of the class path is a scan Core
 * announces with a compiler note.</p>
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

    /**
     * The compiler option selecting the async handler of an async type where more than one handles it, the means
     * of configuration section 10.5 asks a container for: {@code asyncType=handlerClass} pairs separated by commas,
     * a built-in type among them, and {@code asyncType=builtin} for the container's own handler of a built-in type.
     * It is read per compilation: every module compiled with the colliding handlers on its paths repeats it.
     */
    static final String SELECTION_OPTION = "micronaut.cdi.asyncHandlerSelection";

    /**
     * What a selection names for the built-in handler of a type, which it keeps over a registered one.
     */
    static final String BUILT_IN_SELECTION = "builtin";

    static final String RETURN_TYPE = "jakarta.enterprise.invoke.AsyncHandler.ReturnType";
    static final String PARAMETER_TYPE = "jakarta.enterprise.invoke.AsyncHandler.ParameterType";

    /**
     * The built-in return type async types.
     */
    static final Set<String> BUILT_IN = Set.of("java.util.concurrent.CompletionStage",
        "java.util.concurrent.CompletableFuture", "java.util.concurrent.Flow.Publisher");

    private static final List<String> SERVICE_ENTRIES = List.of(
        "META-INF/services/jakarta.enterprise.invoke.AsyncHandler$ReturnType",
        "META-INF/services/jakarta.enterprise.invoke.AsyncHandler$ParameterType");

    private AsyncHandlerTypes() {
    }

    /**
     * The async handlers of the compilation, validated, as invokers are matched against them: the built-in ones and
     * the registered ones, one for each async type. Two handlers of one async type, whatever their kinds and
     * whether one is built in, are a deployment problem unless the compiler option {@link #SELECTION_OPTION}
     * selects one of them for the type, which leaves the others out (section 10.5); a selection that names no
     * registered handler, or one of another async type, is a deployment problem.
     *
     * @param context The compilation
     * @return The handlers, a built-in one with no class
     * @throws DefinitionException Where a handler is malformed
     * @throws DeploymentException Where two handlers handle one type and none is selected, or a selection is wrong
     */
    static List<Handler> of(VisitorContext context) {
        // a name is read with the dots of its canonical form, so that a nested handler a service entry lists as
        // a.B$C and the option names as a.B.C is one handler
        Map<String, String> required = new LinkedHashMap<>();
        for (String name : namesIn(context.getOptions().get(OPTION))) {
            required.putIfAbsent(canonical(name), name);
        }
        for (String resource : SERVICE_ENTRIES) {
            // nothing where the compiler gives no access to its class path: javac not exporting its internals to the
            // processor, which Core reports once, or KSP, which reads none
            for (URL entries : context.getClasspathResources(resource)) {
                for (String name : registered(entries, resource)) {
                    required.putIfAbsent(canonical(name), name);
                }
            }
        }
        // the processor path is read besides the class path of the compilation, as a build compatible extension is
        // found there; a handler registered there and not on the class path is no handler the application can run
        Map<String, String> optional = new LinkedHashMap<>();
        ClassLoader loader = AsyncHandlerTypes.class.getClassLoader();
        for (String resource : SERVICE_ENTRIES) {
            for (String name : registered(loader, resource)) {
                if (!required.containsKey(canonical(name))) {
                    optional.putIfAbsent(canonical(name), name);
                }
            }
        }
        List<Handler> all = new ArrayList<>(BUILT_IN.size() + required.size() + optional.size());
        for (String builtIn : BUILT_IN) {
            all.add(new Handler(null, true, builtIn));
        }
        for (String name : required.values()) {
            ClassElement handler = classElement(name, context)
                .orElseThrow(() -> new DefinitionException("The async handler " + name + " is registered and is not "
                    + "on the classpath of the compilation"));
            all.add(read(handler));
        }
        for (String name : optional.values()) {
            classElement(name, context).ifPresent(handler -> all.add(read(handler)));
        }
        Map<String, String> selected = selectionsIn(context.getOptions().get(SELECTION_OPTION));
        for (Map.Entry<String, String> selection : selected.entrySet()) {
            if (BUILT_IN_SELECTION.equals(selection.getValue())) {
                if (!BUILT_IN.contains(selection.getKey())) {
                    throw new DeploymentException("The container has no built-in async handler of the async type "
                        + selection.getKey() + " to select (section 10.5)");
                }
                continue;
            }
            Handler named = null;
            for (Handler handler : all) {
                if (handler.className() != null && sameName(handler.className(), selection.getValue())) {
                    named = handler;
                }
            }
            if (named == null) {
                throw new DeploymentException("The async handler " + selection.getValue() + " selected for the async "
                    + "type " + selection.getKey() + " is not a registered async handler (section 10.5)");
            }
            if (!named.asyncType().equals(selection.getKey())) {
                throw new DeploymentException("The async handler " + selection.getValue() + " selected for the async "
                    + "type " + selection.getKey() + " handles " + named.asyncType() + " (section 10.5)");
            }
        }
        List<Handler> effective = new ArrayList<>(all.size());
        for (Handler handler : all) {
            String chosen = selected.get(handler.asyncType());
            if (chosen != null) {
                if (handler.className() == null ? BUILT_IN_SELECTION.equals(chosen)
                    : sameName(handler.className(), chosen)) {
                    effective.add(handler);
                }
                continue;
            }
            for (Handler other : effective) {
                if (other.asyncType().equals(handler.asyncType())) {
                    throw new DeploymentException("The async handlers " + describe(other) + " and " + describe(handler)
                        + " both handle the async type " + handler.asyncType() + ", and the compiler option "
                        + SELECTION_OPTION + " selects neither (section 10.5)");
                }
            }
            effective.add(handler);
        }
        return effective;
    }

    private static Optional<ClassElement> classElement(String name, VisitorContext context) {
        return context.getClassElement(name).or(() -> context.getClassElement(canonical(name)));
    }

    private static String canonical(String name) {
        return name.replace('$', '.');
    }

    private static String describe(Handler handler) {
        return handler.className() == null ? "built into the container" : handler.className();
    }

    private static boolean sameName(String className, String named) {
        return className.replace('$', '.').equals(named.replace('$', '.'));
    }

    private static List<String> namesIn(@Nullable String option) {
        List<String> names = new ArrayList<>();
        if (option != null) {
            for (String name : option.split(",")) {
                if (!name.isBlank()) {
                    names.add(name.trim());
                }
            }
        }
        return names;
    }

    private static Map<String, String> selectionsIn(@Nullable String option) {
        Map<String, String> selections = new LinkedHashMap<>();
        for (String selection : namesIn(option)) {
            int equals = selection.indexOf('=');
            if (equals <= 0 || equals == selection.length() - 1) {
                throw new DeploymentException("The compiler option " + SELECTION_OPTION + " selects a handler as "
                    + "asyncType=handlerClass, not as " + selection);
            }
            selections.put(selection.substring(0, equals).trim().replace('$', '.'),
                selection.substring(equals + 1).trim());
        }
        return selections;
    }

    /**
     * Whether a handler exists for the async type: a built-in one or a registered one.
     *
     * @param asyncType The name of the async type
     * @param handlers  The handlers of the compilation ({@link #of})
     * @return Whether one exists
     */
    static boolean exists(String asyncType, List<Handler> handlers) {
        String type = asyncType.replace('$', '.');
        for (Handler handler : handlers) {
            if (handler.asyncType().equals(type)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The async handler a method matches, which is what makes it asynchronous: exactly one, or none.
     *
     * @param method   The target method of an invoker
     * @param handlers The handlers of the compilation ({@link #of})
     * @return The match, or {@code null} where the method is not asynchronous
     */
    static @Nullable Match match(MethodElement method, List<Handler> handlers) {
        List<Match> matches = new ArrayList<>(1);
        String returned = method.getReturnType().getName().replace('$', '.');
        ParameterElement[] parameters = method.getParameters();
        for (Handler handler : handlers) {
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
                names.addAll(registered(files.nextElement(), resource));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("The service entries " + resource + " could not be read", e);
        }
        return names;
    }

    private static List<String> registered(URL file, String resource) {
        // Core reads service entries only behind its service loader, which has nothing to hand a compilation
        List<String> names = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(file.openStream(), StandardCharsets.UTF_8))) {
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                int comment = line.indexOf('#');
                String name = (comment < 0 ? line : line.substring(0, comment)).trim();
                if (!name.isEmpty()) {
                    names.add(name);
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
     * @param className  The handler class, {@code null} for a built-in one
     * @param returnType Whether it is a return type handler, rather than a parameter type one
     * @param asyncType  The erasure of its async type
     */
    record Handler(@Nullable String className, boolean returnType, String asyncType) {
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
