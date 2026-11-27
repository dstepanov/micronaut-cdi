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

            public String greet(String name) {
                return name;
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
    void aContextForTheSingletonPseudoScopeIsADeploymentProblem() {
        String errors = errorsOf(SingletonScopeContext.class);
        assertTrue(errors.contains(VisitorMessages.DEPLOYMENT_PROBLEM + "The pseudo-scope "
            + jakarta.inject.Singleton.class.getName() + " is implemented by the container"), errors);
        assertFalse(errors.contains("Error initializing type visitor"), errors);
    }

    @Test
    void anInvokerOfAPrivateMethodIsADeploymentProblem() {
        String errors = errorsOf(PrivateInvoker.class);
        // reported as the deployment problem a registration method that throws is, naming what the invoker refused
        assertTrue(errors.contains(VisitorMessages.DEPLOYMENT_PROBLEM), errors);
        assertTrue(errors.contains("The private method reported.Target#hidden cannot be invoked"), errors);
        assertFalse(errors.contains("Error finishing the round of type visitor"), errors);
    }

    @Test
    void anExceptionADiscoveryMethodThrowsIsADeploymentProblem() {
        String errors = errorsOf(ThrowingDiscovery.class);
        assertTrue(errors.contains(VisitorMessages.DEPLOYMENT_PROBLEM), errors);
        assertTrue(errors.contains("discovery failed on purpose"), errors);
    }

    @Test
    void anExceptionAnEnhancementMethodThrowsIsADeploymentProblem() {
        String errors = errorsOf(ThrowingEnhancement.class);
        assertTrue(errors.contains(VisitorMessages.DEPLOYMENT_PROBLEM), errors);
        assertTrue(errors.contains("enhancement failed on purpose"), errors);
    }

    @Test
    void anExceptionARegistrationMethodThrowsIsADeploymentProblem() {
        String errors = errorsOf(ThrowingRegistration.class);
        assertTrue(errors.contains(VisitorMessages.DEPLOYMENT_PROBLEM), errors);
        assertTrue(errors.contains("registration failed on purpose"), errors);
    }

    @Test
    void aParameterOfATypeThePhaseDoesNotHandIsADeploymentProblem() {
        String errors = errorsOf(UnsupportedParameter.class);
        assertTrue(errors.contains(VisitorMessages.DEPLOYMENT_PROBLEM), errors);
        assertTrue(errors.contains("which the phase does not hand to one"), errors);
    }

    @Test
    void anArgumentLookupOutOfRangeIsRefusedAsItIsAskedFor() {
        LookupOutOfRange.REFUSED.clear();
        String errors = errorsOf(LookupOutOfRange.class);
        assertTrue(LookupOutOfRange.REFUSED.stream().allMatch(IllegalArgumentException.class::isInstance)
            && !LookupOutOfRange.REFUSED.isEmpty(), "withArgumentLookup threw " + LookupOutOfRange.REFUSED);
        // the registration method let it through, which fails the deployment
        assertTrue(errors.contains(VisitorMessages.DEPLOYMENT_PROBLEM), errors);
    }

    @Test
    void anErrorARegistrationMethodReportsIsADeploymentProblem() {
        String errors = errorsOf(ReportingRegistration.class);
        assertTrue(errors.contains(VisitorMessages.DEPLOYMENT_PROBLEM + "reported on purpose"), errors);
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
     * A registration method that reports an error through its messages.
     */
    public static final class ReportingRegistration implements BuildCompatibleExtension {
        @Registration(types = Object.class)
        public void register(BeanInfo bean, jakarta.enterprise.inject.build.compatible.spi.Messages messages) {
            if (bean.isClassBean() && bean.declaringClass().name().equals("reported.Target")) {
                messages.error("reported on purpose");
            }
        }
    }

    /**
     * A discovery method that throws.
     */
    public static final class ThrowingDiscovery implements BuildCompatibleExtension {
        @Discovery
        public void discover(MetaAnnotations meta) {
            throw new IllegalStateException("discovery failed on purpose");
        }
    }

    /**
     * An enhancement method that throws.
     */
    public static final class ThrowingEnhancement implements BuildCompatibleExtension {
        @jakarta.enterprise.inject.build.compatible.spi.Enhancement(types = Object.class, withSubtypes = true)
        public void enhance(jakarta.enterprise.inject.build.compatible.spi.ClassConfig type) {
            if (type.info().name().equals("reported.Target")) {
                throw new IllegalStateException("enhancement failed on purpose");
            }
        }
    }

    /**
     * A registration method that throws.
     */
    public static final class ThrowingRegistration implements BuildCompatibleExtension {
        @Registration(types = Object.class)
        public void register(BeanInfo bean) {
            if (bean.isClassBean() && bean.declaringClass().name().equals("reported.Target")) {
                throw new IllegalStateException("registration failed on purpose");
            }
        }
    }

    /**
     * An enhancement method asking for what the phase does not hand.
     */
    public static final class UnsupportedParameter implements BuildCompatibleExtension {
        @jakarta.enterprise.inject.build.compatible.spi.Enhancement(types = Object.class, withSubtypes = true)
        public void enhance(jakarta.enterprise.inject.build.compatible.spi.ClassConfig type,
                            jakarta.enterprise.inject.build.compatible.spi.SyntheticComponents components) {
        }
    }

    /**
     * Asks for an argument lookup out of the method's parameters, recording what the builder threw, and lets it
     * through.
     */
    public static final class LookupOutOfRange implements BuildCompatibleExtension {

        static final List<RuntimeException> REFUSED = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Registration(types = Object.class)
        public void register(BeanInfo bean, InvokerFactory invokers) {
            if (bean.isClassBean() && bean.declaringClass().name().equals("reported.Target")) {
                MethodInfo greet = bean.declaringClass().methods().stream()
                    .filter(method -> method.name().equals("greet")).findFirst().orElseThrow();
                try {
                    invokers.createInvoker(bean, greet).withArgumentLookup(3);
                } catch (RuntimeException e) {
                    REFUSED.add(e);
                    throw e;
                }
            }
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
     * Registers a context for the singleton pseudo-scope, which the container implements itself.
     */
    public static final class SingletonScopeContext implements BuildCompatibleExtension {
        @Discovery
        public void discover(MetaAnnotations meta) {
            meta.addContext(jakarta.inject.Singleton.class, Global.class);
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
