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
package io.micronaut.cdi.reflection;

import io.micronaut.cdi.runtime.CdiBean;
import io.micronaut.cdi.runtime.extension.RegistrationPhase;
import io.micronaut.context.BeanContext;
import io.micronaut.core.annotation.Internal;
import io.micronaut.inject.BeanDefinition;
import jakarta.enterprise.inject.build.compatible.spi.BeanInfo;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.Messages;
import jakarta.enterprise.inject.build.compatible.spi.Registration;
import jakarta.inject.Singleton;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Describes to the registration phase the beans the compiler never saw.
 *
 * <p>Every bean written as a class is described to the phase as it is compiled, which costs nothing because the
 * compiler is holding the description anyway. A synthetic bean has no class that was compiled: an extension
 * described it, and the only thing left to read it back from is the class it is created as. That is what this
 * does, and why it is not part of the runtime — reading a class back is what Micronaut is built to avoid, so an
 * application says it wants it by adding this module.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Singleton
@Internal
public final class ReflectiveRegistrationPhase implements RegistrationPhase {

    private final BeanContext beanContext;

    public ReflectiveRegistrationPhase(BeanContext beanContext) {
        this.beanContext = beanContext;
    }

    @Override
    public void run(List<BuildCompatibleExtension> extensions,
                    List<BeanDefinition<?>> beans,
                    Messages messages) {
        for (BeanDefinition<?> definition : beans) {
            BeanInfo bean = new CdiBeanInfo(new CdiBean<>(beanContext, cast(definition)));
            for (BuildCompatibleExtension extension : extensions) {
                for (Method method : extension.getClass().getDeclaredMethods()) {
                    Registration registration = method.getAnnotation(Registration.class);
                    if (registration != null && matches(registration, definition)) {
                        method.setAccessible(true);
                        describe(extension, method, bean, messages, beanContext);
                    }
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static BeanDefinition<Object> cast(BeanDefinition<?> definition) {
        return (BeanDefinition<Object>) definition;
    }

    private static boolean matches(Registration registration, BeanDefinition<?> definition) {
        for (Class<?> type : registration.types()) {
            if (type.isAssignableFrom(definition.getBeanType())) {
                return true;
            }
        }
        return false;
    }

    private static void describe(BuildCompatibleExtension extension,
                                 Method method,
                                 BeanInfo bean,
                                 Messages messages,
                                 BeanContext beanContext) {
        Class<?>[] parameterTypes = method.getParameterTypes();
        for (Class<?> parameterType : parameterTypes) {
            if (parameterType.equals(jakarta.enterprise.inject.build.compatible.spi.ObserverInfo.class)) {
                // this pass describes beans; a method asking about observers has nothing to hear here
                return;
            }
        }
        Object[] arguments = new Object[parameterTypes.length];
        for (int i = 0; i < parameterTypes.length; i++) {
            if (parameterTypes[i].equals(Messages.class)) {
                arguments[i] = messages;
            } else if (parameterTypes[i].equals(BeanInfo.class)) {
                arguments[i] = bean;
            } else if (parameterTypes[i].equals(jakarta.enterprise.inject.build.compatible.spi.Types.class)) {
                arguments[i] = new ReflectiveTypesFactory(beanContext);
            } else {
                messages.error("The registration method " + method + " asks for a "
                    + parameterTypes[i].getName() + ", which this module does not hand to one");
                return;
            }
        }
        try {
            method.invoke(extension, arguments);
        } catch (IllegalAccessException e) {
            messages.error("The registration method " + method + " could not be invoked: " + e);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            messages.error("The registration method " + method + " failed: " + cause);
        }
    }
}
