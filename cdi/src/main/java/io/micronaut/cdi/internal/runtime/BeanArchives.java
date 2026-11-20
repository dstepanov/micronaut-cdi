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

import io.micronaut.core.annotation.Internal;
import io.micronaut.inject.BeanType;
import jakarta.enterprise.inject.spi.DeploymentException;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The bean archive descriptors of an application: the {@code META-INF/beans.xml} of every archive a class loader
 * reaches. An implementation of CDI Lite ignores a descriptor except for its bean discovery mode (CDI 5.0, section
 * 2.5.1), so the mode is all that is read of one: {@code none} makes an archive no bean archive, which the SE
 * bootstrap leaves out of a container that discovers, and {@code all} belongs to CDI Full and is a deployment
 * problem, reported as any container starts.
 *
 * <p>An archive is the prefix of the URL its descriptor was found at, which every resource of the archive's URL
 * starts with: a jar, or a directory of classes with its descriptor beside them. A bean belongs to the archive its
 * definition's class is loaded from, since the processor generates the definition beside the class.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class BeanArchives {

    private static final String BEANS_XML = "META-INF/beans.xml";
    private static final Pattern COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);
    private static final Pattern DISCOVERY_MODE =
        Pattern.compile("bean-discovery-mode\\s*=\\s*[\"']\\s*([A-Za-z]*)\\s*[\"']");

    private BeanArchives() {
    }

    /**
     * The descriptors the class loader reaches.
     *
     * @param loader The class loader of the application, or {@code null} for the system class loader
     * @return The descriptors, in the order the loader lists them
     * @throws DeploymentException Where one cannot be read
     */
    public static List<Descriptor> descriptors(@Nullable ClassLoader loader) {
        List<Descriptor> descriptors = new ArrayList<>(0);
        try {
            Enumeration<URL> found = loader != null ? loader.getResources(BEANS_XML)
                : ClassLoader.getSystemResources(BEANS_XML);
            while (found.hasMoreElements()) {
                URL location = found.nextElement();
                String content;
                try (InputStream in = location.openStream()) {
                    content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
                String url = location.toExternalForm();
                descriptors.add(new Descriptor(url.substring(0, url.length() - BEANS_XML.length()),
                    discoveryModeOf(content)));
            }
        } catch (IOException e) {
            throw new DeploymentException("The bean archive descriptors could not be read", e);
        }
        return descriptors;
    }

    /**
     * The archives whose descriptor declares the bean discovery mode {@code none}: no bean archives.
     *
     * @param loader The class loader of the application
     * @return The archives
     */
    public static List<String> discoveringNothing(@Nullable ClassLoader loader) {
        List<String> roots = new ArrayList<>(0);
        for (Descriptor descriptor : descriptors(loader)) {
            if (descriptor.discoversNothing()) {
                roots.add(descriptor.archive());
            }
        }
        return roots;
    }

    /**
     * Refuses an application with an archive whose descriptor declares the bean discovery mode {@code all}, which an
     * implementation of CDI Lite alone is required to detect and to treat as a deployment problem (section 2.5.1).
     *
     * @param loader The class loader of the application
     * @throws DeploymentException Where an archive discovers all its classes
     */
    public static void requireNoneDiscoversAll(@Nullable ClassLoader loader) {
        List<String> all = new ArrayList<>(0);
        for (Descriptor descriptor : descriptors(loader)) {
            if (descriptor.discoversAll()) {
                all.add(descriptor.archive());
            }
        }
        if (!all.isEmpty()) {
            throw new DeploymentException("The bean discovery mode all belongs to CDI Full, and the beans.xml of "
                + String.join(", ", all) + " declares it (CDI 5.0 section 2.5.1)");
        }
    }

    /**
     * Whether the bean was compiled into one of the given archives: its definition is generated beside its class, so
     * the archive the definition's class is loaded from is the bean's.
     *
     * @param bean  The bean
     * @param roots The archives
     * @return Whether it is in one of them
     */
    public static boolean isCompiledIntoAnyOf(BeanType<?> bean, List<String> roots) {
        if (roots.isEmpty()) {
            return false;
        }
        Class<?> definition = bean.getClass();
        ClassLoader loader = definition.getClassLoader();
        String resource = definition.getName().replace('.', '/') + ".class";
        URL location = loader != null ? loader.getResource(resource) : ClassLoader.getSystemResource(resource);
        if (location == null) {
            return false;
        }
        String url = location.toExternalForm();
        for (String root : roots) {
            if (url.startsWith(root)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The bean discovery mode a descriptor declares, read outside its comments, or {@code null} where it declares
     * none - an empty descriptor among them - which CDI 4.0 and later read as {@code annotated}.
     */
    static @Nullable String discoveryModeOf(String content) {
        Matcher mode = DISCOVERY_MODE.matcher(COMMENT.matcher(content).replaceAll(""));
        return mode.find() ? mode.group(1).toLowerCase(Locale.ROOT) : null;
    }

    /**
     * The descriptor of one archive.
     *
     * @param archive The archive, as the prefix of the URLs of its resources
     * @param mode    The bean discovery mode it declares, or {@code null} where it declares none
     */
    public record Descriptor(String archive, @Nullable String mode) {

        /**
         * @return Whether the archive is no bean archive
         */
        public boolean discoversNothing() {
            return "none".equals(mode);
        }

        /**
         * @return Whether the archive declares the mode of CDI Full
         */
        public boolean discoversAll() {
            return "all".equals(mode);
        }
    }
}
