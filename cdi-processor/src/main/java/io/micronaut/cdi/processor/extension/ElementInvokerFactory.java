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

import io.micronaut.cdi.runtime.RecordedInvoker;
import io.micronaut.core.annotation.Internal;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.MethodElement;
import io.micronaut.inject.ast.ParameterElement;
import jakarta.enterprise.inject.build.compatible.spi.BeanInfo;
import jakarta.enterprise.inject.build.compatible.spi.InvokerFactory;
import jakarta.enterprise.inject.build.compatible.spi.InvokerInfo;
import jakarta.enterprise.inject.spi.DeploymentException;
import jakarta.enterprise.invoke.InvokerBuilder;
import jakarta.enterprise.lang.model.declarations.MethodInfo;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Builds the invokers of CDI 4.1's registration phase, while the bean is being compiled.
 *
 * <p>What can be invoked is checked here, where the registration runs: an invoker is for a method of a managed
 * bean — not a producer's bean, not an interceptor, not a constructor, not a private method, and of the methods
 * every object has only {@code toString}. Building one marks the method executable, so that the compiled bean
 * definition carries it and the invocation at runtime reads what was compiled.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
final class ElementInvokerFactory implements InvokerFactory {

    @Override
    public InvokerBuilder<InvokerInfo> createInvoker(BeanInfo bean, MethodInfo method) {
        if (!(bean instanceof ElementBeanInfo beanInfo) || !(method instanceof ElementMethodInfo methodInfo)) {
            throw new DeploymentException("An invoker is built from the bean and method of the registration "
                + "phase that is running, not from ones described elsewhere");
        }
        if (!beanInfo.isClassBean()) {
            throw new DeploymentException("An invoker invokes a method of a managed bean; the bean of the "
                + "producer " + beanInfo + " is not one");
        }
        if (beanInfo.isInterceptor()) {
            throw new DeploymentException("An invoker invokes a method of a managed bean, not of the "
                + "interceptor " + beanInfo);
        }
        MethodElement element = methodInfo.methodElement();
        if (methodInfo.isConstructor()) {
            throw new DeploymentException("An invoker invokes a method; "
                + element.getDeclaringType().getName() + "'s constructor is not one");
        }
        if (element.isPrivate()) {
            throw new DeploymentException("The private method " + element.getDeclaringType().getName() + "#"
                + element.getName() + " cannot be invoked through an invoker");
        }
        String declaredBy = element.getDeclaringType().getName();
        if ("java.lang.Object".equals(declaredBy) && !"toString".equals(element.getName())) {
            throw new DeploymentException("Of the methods every object has, only toString may be invoked "
                + "through an invoker, not " + element.getName());
        }
        ClassElement beanClass = beanInfo.beanType();
        if (!beanClass.isAssignable(element.getDeclaringType())) {
            throw new DeploymentException("The method " + declaredBy + "#" + element.getName()
                + " is not a method of the bean " + beanClass.getName());
        }
        return new ElementInvokerBuilder(beanClass, element);
    }

    /**
     * The builder handed back for one method: what the extension may ask to be looked up, and the build that
     * records it.
     */
    private static final class ElementInvokerBuilder implements InvokerBuilder<InvokerInfo> {

        private final ClassElement beanClass;
        private final MethodElement method;
        private final Set<Integer> argumentLookups = new LinkedHashSet<>();
        private boolean instanceLookup;

        private ElementInvokerBuilder(ClassElement beanClass, MethodElement method) {
            this.beanClass = beanClass;
            this.method = method;
        }

        @Override
        public InvokerBuilder<InvokerInfo> withInstanceLookup() {
            instanceLookup = true;
            return this;
        }

        @Override
        public InvokerBuilder<InvokerInfo> withArgumentLookup(int position) {
            argumentLookups.add(position);
            return this;
        }

        @Override
        public InvokerInfo build() {
            ParameterElement[] parameters = method.getParameters();
            for (int position : argumentLookups) {
                if (position < 0 || position >= parameters.length) {
                    throw new DeploymentException("The argument lookup at " + position + " is out of the "
                        + "method's parameters: " + beanClass.getName() + "#" + method.getName() + " takes "
                        + parameters.length);
                }
            }
            // marked executable so that the compiled definition carries the method for the invocation to read
            method.annotate(io.micronaut.context.annotation.Executable.class);
            String[] parameterTypeNames = new String[parameters.length];
            boolean[] lookups = new boolean[parameters.length];
            for (int i = 0; i < parameters.length; i++) {
                io.micronaut.inject.ast.ClassElement parameterType = parameters[i].getType();
                StringBuilder name = new StringBuilder(parameterType.getName());
                for (io.micronaut.inject.ast.ClassElement component = parameterType;
                     component.isArray(); component = component.fromArray()) {
                    name.append("[]");
                }
                parameterTypeNames[i] = name.toString();
                lookups[i] = argumentLookups.contains(i);
            }
            return new RecordedInvoker(beanClass.getName(), method.getName(), parameterTypeNames,
                method.isStatic(), instanceLookup, lookups);
        }
    }
}
