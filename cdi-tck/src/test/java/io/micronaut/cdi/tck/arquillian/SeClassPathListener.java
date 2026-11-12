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

import org.jboss.arquillian.container.se.api.ClassPath;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.shrinkwrap.api.Archive;
import org.testng.IClassListener;
import org.testng.ITestClass;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Restricts the SE bootstrap to the class path an SE test of the kit declares, where the test runs without
 * Arquillian.
 *
 * <p>An SE test is written for a freshly launched JVM holding only its archive, the {@code ClassPath} its
 * {@code Deployment} method builds. One that extends Arquillian is deployed through the adapter, which restricts
 * the bootstrap to that archive; one that does not - {@code StartupShutdownTest}, which bootstraps a container
 * with discovery on - would otherwise bootstrap over the whole class path of every scenario, which is no
 * deployment. So the archive is built here, before the class runs, and the restriction lifted after it.</p>
 */
public final class SeClassPathListener implements IClassListener {

    /**
     * The loader of what the archive of the running class compiled with its extensions, and the context loader of
     * the thread before it.
     */
    private @org.jspecify.annotations.Nullable DeploymentClassLoader compiled;
    private @org.jspecify.annotations.Nullable ClassLoader contextLoaderBefore;

    @Override
    public void onBeforeClass(ITestClass testClass) {
        Class<?> type = testClass.getRealClass();
        if (org.jboss.arquillian.testng.Arquillian.class.isAssignableFrom(type)) {
            return;
        }
        for (Method method : type.getDeclaredMethods()) {
            if (method.isAnnotationPresent(Deployment.class) && Modifier.isStatic(method.getModifiers())) {
                try {
                    Object archive = method.invoke(null);
                    if (archive instanceof Archive<?> built && ClassPath.isRepresentedBy(built)) {
                        System.setProperty(io.micronaut.cdi.se.MicronautSeContainerInitializer.RESTRICTED_CLASSPATH,
                            String.join(",", MicronautDeployableContainer.classesOf(built)));
                        // an archive that registers build compatible extensions was compiled with them
                        DeploymentClassLoader loader = MicronautDeployableContainer.compileForTheBootstrap(built);
                        if (loader != null) {
                            compiled = loader;
                            contextLoaderBefore = Thread.currentThread().getContextClassLoader();
                            Thread.currentThread().setContextClassLoader(loader);
                        }
                    }
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException("The deployment of " + type.getName()
                        + " could not be built", e);
                }
                return;
            }
        }
    }

    @Override
    public void onAfterClass(ITestClass testClass) {
        if (!org.jboss.arquillian.testng.Arquillian.class.isAssignableFrom(testClass.getRealClass())) {
            System.clearProperty(io.micronaut.cdi.se.MicronautSeContainerInitializer.RESTRICTED_CLASSPATH);
            DeploymentClassLoader loader = compiled;
            if (loader != null) {
                compiled = null;
                Thread.currentThread().setContextClassLoader(contextLoaderBefore);
                contextLoaderBefore = null;
                loader.dispose();
            }
        }
    }
}
