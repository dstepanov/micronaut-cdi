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
package io.micronaut.cdi.test.bce;

import io.micronaut.annotation.processing.test.JavaParser;
import io.micronaut.cdi.processor.extension.VisitorMessages;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.spi.AlterableContext;
import jakarta.enterprise.context.spi.Contextual;
import jakarta.enterprise.context.spi.CreationalContext;
import jakarta.enterprise.inject.build.compatible.spi.BeanInfo;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.Discovery;
import jakarta.enterprise.inject.build.compatible.spi.InvokerFactory;
import jakarta.enterprise.inject.build.compatible.spi.MetaAnnotations;
import jakarta.enterprise.inject.build.compatible.spi.Registration;
import jakarta.enterprise.inject.build.compatible.spi.Types;
import jakarta.enterprise.lang.model.declarations.MethodInfo;
import org.junit.jupiter.api.Test;

import javax.tools.Diagnostic;
import java.lang.annotation.Annotation;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A malformed extension method, a context an extension registers for a global scope and an invoker of a method that
 * cannot be invoked are reported by the processor as the definition error or the deployment problem they are, as any
 * other finding about the deployment, rather than as a visitor that failed: a crash of the compilation says nothing
 * of the deployment, and a harness that tells the two apart would read these as one.
 */
class ExtensionErrorReportingTest {

    private static final String SOURCE = """
        package reported;

        @jakarta.enterprise.context.Dependent
        public class Target {

            private void hidden() {
            }
        }
        """;

    @Test
    void aMalformedExtensionMethodIsADefinitionError() {
        String errors = errorsOf(MalformedRegistration.class);
        assertTrue(errors.contains("must declare exactly one parameter naming what it is told about"), errors);
        assertFalse(errors.contains("Error initializing type visitor"), errors);
        assertFalse(errors.contains(VisitorMessages.DEPLOYMENT_PROBLEM), errors);
    }

    @Test
    void aContextForAGlobalScopeIsADeploymentProblem() {
        String errors = errorsOf(GlobalScopeContext.class);
        assertTrue(errors.contains(VisitorMessages.DEPLOYMENT_PROBLEM + "The scope "
            + ApplicationScoped.class.getName() + " is global"), errors);
        assertFalse(errors.contains("Error initializing type visitor"), errors);
    }

    @Test
    void anInvokerOfAPrivateMethodIsADeploymentProblem() {
        String errors = errorsOf(PrivateInvoker.class);
        assertTrue(errors.contains(VisitorMessages.DEPLOYMENT_PROBLEM + "The private method reported.Target#hidden"),
            errors);
        assertFalse(errors.contains("Error finishing the round of type visitor"), errors);
    }

    private static String errorsOf(Class<? extends BuildCompatibleExtension> extension) {
        try (JavaParser parser = ExtensionCompilation.of(extension)) {
            assertThrows(RuntimeException.class, () -> parser.generate("reported.Target", SOURCE),
                "the deployment is refused as it compiles");
            return parser.getDiagnosticCollector().getDiagnostics().stream()
                .filter(diagnostic -> diagnostic.getKind() == Diagnostic.Kind.ERROR)
                .map(diagnostic -> diagnostic.getMessage(null))
                .collect(Collectors.joining("\n"));
        }
    }

    /**
     * Declares a registration method told about nothing.
     */
    public static final class MalformedRegistration implements BuildCompatibleExtension {
        @Registration(types = Object.class)
        public void register(Types types) {
        }
    }

    /**
     * Registers a context for the application scope, which is global.
     */
    public static final class GlobalScopeContext implements BuildCompatibleExtension {
        @Discovery
        public void discover(MetaAnnotations meta) {
            meta.addContext(ApplicationScoped.class, Global.class);
        }
    }

    /**
     * The context registered for a global scope, never instantiated.
     */
    public static final class Global implements AlterableContext {
        @Override
        public Class<? extends Annotation> getScope() {
            return ApplicationScoped.class;
        }

        @Override
        public <T> T get(Contextual<T> contextual, CreationalContext<T> creationalContext) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> T get(Contextual<T> contextual) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isActive() {
            return true;
        }

        @Override
        public void destroy(Contextual<?> contextual) {
        }
    }

    /**
     * Builds an invoker of a private method.
     */
    public static final class PrivateInvoker implements BuildCompatibleExtension {
        @Registration(types = Object.class)
        public void register(BeanInfo bean, InvokerFactory invokers) {
            if (bean.isClassBean() && bean.declaringClass().name().equals("reported.Target")) {
                List<MethodInfo> hidden = bean.declaringClass().methods().stream()
                    .filter(method -> method.name().equals("hidden")).toList();
                invokers.createInvoker(bean, hidden.get(0)).build();
            }
        }
    }
}
