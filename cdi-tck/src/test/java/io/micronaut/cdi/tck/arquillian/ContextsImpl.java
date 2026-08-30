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
package io.micronaut.cdi.tck.arquillian;

import io.micronaut.cdi.context.RequestScope;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.context.spi.Context;
import jakarta.enterprise.inject.spi.BeanManager;
import org.jboss.cdi.tck.spi.Contexts;

/**
 * The contexts the kit turns on and off around its assertions: the request context, mostly, and the dependent
 * one to compare against.
 */
public final class ContextsImpl implements Contexts<Context> {

    @Override
    public void setActive(Context context) {
        if (RequestScoped.class.equals(context.getScope())) {
            requestScope().resume();
        }
    }

    @Override
    public void setInactive(Context context) {
        if (RequestScoped.class.equals(context.getScope())) {
            // inactive is not over: the kit reactivates the context and expects what it held to still be
            // there, so the context is suspended rather than ended
            requestScope().suspend();
        }
    }

    @Override
    public Context getRequestContext() {
        return manager().getContext(RequestScoped.class);
    }

    @Override
    public Context getDependentContext() {
        return manager().getContext(Dependent.class);
    }

    @Override
    public void destroyContext(Context context) {
        if (RequestScoped.class.equals(context.getScope())) {
            // destroying is the one that ends the context and everything it held, unlike setInactive
            requestScope().deactivate();
        }
    }

    private static BeanManager manager() {
        return CurrentDeployment.context().getBean(BeanManager.class);
    }

    private static RequestScope requestScope() {
        return CurrentDeployment.context().getBean(RequestScope.class);
    }
}
