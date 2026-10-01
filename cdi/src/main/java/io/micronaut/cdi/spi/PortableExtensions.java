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
package io.micronaut.cdi.spi;

import io.micronaut.context.BeanContext;
import jakarta.enterprise.inject.spi.Extension;

import java.util.List;
import java.util.function.Predicate;

/**
 * Runs the portable extensions an SE bootstrap was handed, or finds through the service loader, over the beans
 * that were compiled.
 *
 * <p>A portable extension belongs to CDI Full (section 3.9): an ordinary class whose observer methods are found
 * by reading it, notified of events that describe a deployment as it is discovered. A subset of that lifecycle
 * can be answered by a container whose beans are already compiled, and it is answered by the optional module that
 * reads classes, {@code micronaut-cdi-reflection}, which implements this interface. It is an interface of its own
 * rather than part of {@code CdiReflection}: that one answers single questions about a class for the API of the
 * specification's Lite profile, and this runs a lifecycle that only a bootstrap asks for.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
public interface PortableExtensions {

    /**
     * The message of the error a bootstrap that was handed an extension fails with where the module is missing.
     */
    String MISSING = "A portable extension is run by the optional module "
        + "io.micronaut.cdi:micronaut-cdi-reflection, which is not on the classpath. The extensions of CDI Lite "
        + "are build compatible ones, found through the service loader while the application compiles";

    /**
     * Runs the lifecycle of the extensions against a container that is starting, before the application is told
     * it has started.
     *
     * @param context The context that is starting
     * @param request What the bootstrap asked for
     * @throws jakarta.enterprise.inject.spi.DefinitionException Where an extension registered a definition error
     * @throws jakarta.enterprise.inject.spi.DeploymentException Where an extension registered a deployment problem
     * @throws UnsupportedOperationException                     Where an extension asked for a change to a bean
     *                                                           that was compiled
     */
    void run(BeanContext context, Request request);

    /**
     * What an SE bootstrap asked of the portable extensions.
     *
     * @param instances   The extension instances the program handed over
     * @param classes     The extension classes the program named
     * @param classLoader Where the service loader looks for the others
     * @param admitted    Which of the extensions the service loader finds take part, by class name
     */
    record Request(List<Extension> instances,
                   List<Class<? extends Extension>> classes,
                   ClassLoader classLoader,
                   Predicate<String> admitted) {

        /**
         * Whether the program named any extension itself.
         *
         * @return Whether there is an explicit extension
         */
        public boolean namesExtensions() {
            return !instances.isEmpty() || !classes.isEmpty();
        }
    }
}
