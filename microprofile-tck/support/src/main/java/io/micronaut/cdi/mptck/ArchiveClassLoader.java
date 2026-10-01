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
package io.micronaut.cdi.mptck;

import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.api.ArchivePath;
import org.jboss.shrinkwrap.api.Node;
import org.jboss.shrinkwrap.api.asset.ArchiveAsset;
import org.jboss.shrinkwrap.api.asset.Asset;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;

/**
 * The application class loader of a deployment: what the archive holds as resources - the property files, the
 * service files, the files a test adds - is seen through the loader as it would be in the archive, over the classpath
 * the beans are compiled into.
 *
 * <p>The classes of the archive are not loaded from it: they are compiled here already, and the archive only says
 * which of them the deployment holds. A resource of a web archive is at the root of its classes, in
 * {@code WEB-INF/classes}, and in each library under {@code WEB-INF/lib}; a Java archive has them at its root.</p>
 */
final class ArchiveClassLoader extends java.net.URLClassLoader {

    private static final String CLASSES = "WEB-INF/classes/";

    private final Map<String, List<byte[]>> resources = new java.util.LinkedHashMap<>();

    ArchiveClassLoader(Archive<?> archive, URL generated, ClassLoader parent) {
        super(new URL[]{generated}, parent);
        collect(archive);
    }

    private void collect(Archive<?> archive) {
        for (Map.Entry<ArchivePath, Node> entry : archive.getContent().entrySet()) {
            String path = entry.getKey().get().substring(1);
            Asset asset = entry.getValue().getAsset();
            if (asset == null) {
                continue;
            }
            if (path.endsWith(".jar") && asset instanceof ArchiveAsset nested) {
                collect(nested.getArchive());
                continue;
            }
            if (path.endsWith(".class")) {
                continue;
            }
            try (InputStream in = asset.openStream()) {
                byte[] bytes = in.readAllBytes();
                add(path, bytes);
                if (path.startsWith(CLASSES)) {
                    add(path.substring(CLASSES.length()), bytes);
                }
            } catch (IOException e) {
                throw new java.io.UncheckedIOException("The resource " + path + " of the archive could not be read", e);
            }
        }
    }

    private void add(String name, byte[] bytes) {
        resources.computeIfAbsent(name, key -> new ArrayList<>()).add(bytes);
    }

    @Override
    public URL getResource(String name) {
        name = resourceName(name);
        List<byte[]> own = resources.get(name);
        if (own != null) return urlOf(name, 0, own.get(0));
        if (name.equals("META-INF/microprofile-config.properties")) return null;
        return super.getResource(name);
    }

    @Override
    public Enumeration<URL> getResources(String name) throws IOException {
        name = resourceName(name);
        List<URL> urls = new ArrayList<>();
        List<byte[]> own = resources.get(name);
        if (own != null) {
            for (int i = 0; i < own.size(); i++) {
                urls.add(urlOf(name, i, own.get(i)));
            }
        }
        // Deployment resources and service providers must not leak from other TCK scenarios.
        boolean archiveOnly = name.equals("META-INF/microprofile-config.properties")
            || name.equals("META-INF/services/jakarta.enterprise.inject.spi.Extension")
            || name.equals("META-INF/services/jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension");
        if (!archiveOnly) {
            for (URL url : Collections.list(super.getResources(name))) {
                if (!name.startsWith("META-INF/services/") || !url.toString().contains("-tck-")) {
                    urls.add(url);
                }
            }
        }
        return Collections.enumeration(urls);
    }

    @Override
    public InputStream getResourceAsStream(String name) {
        name = resourceName(name);
        List<byte[]> own = resources.get(name);
        if (own != null) return new ByteArrayInputStream(own.get(0));
        if (name.equals("META-INF/microprofile-config.properties")) return null;
        return super.getResourceAsStream(name);
    }

    private static String resourceName(String name) {
        // The REST Client TCK and its implementation use servlet-style absolute resource paths.
        return name.startsWith("/") ? name.substring(1) : name;
    }

    private static URL urlOf(String name, int index, byte[] bytes) {
        try {
            return new URL("archive", null, -1, "/" + name + "#" + index, new URLStreamHandler() {
                @Override
                protected URLConnection openConnection(URL url) {
                    return new URLConnection(url) {
                        @Override
                        public void connect() {
                            // the bytes are at hand
                        }

                        @Override
                        public InputStream getInputStream() {
                            return new ByteArrayInputStream(bytes);
                        }
                    };
                }
            });
        } catch (java.net.MalformedURLException e) {
            throw new IllegalStateException(e);
        }
    }
}
