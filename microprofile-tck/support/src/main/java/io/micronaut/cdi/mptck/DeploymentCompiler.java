/* Licensed under the Apache License, Version 2.0. */
package io.micronaut.cdi.mptck;

import javax.tools.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Runs the production processors on precisely the source classes belonging to one deployment. */
final class DeploymentCompiler {
    static Path compile(Set<String> classes, Path output, List<String> imports) throws IOException {
        Files.createDirectories(output);
        Path sources = Path.of(System.getProperty("mp.tck.sources"));
        Set<java.io.File> files = new LinkedHashSet<>();
        for (String name : classes) {
            int inner = name.indexOf('$');
            if (inner >= 0) name = name.substring(0, inner);
            Path source = sources.resolve(name.replace('.', '/') + ".java");
            if (Files.isRegularFile(source)) files.add(source.toFile());
        }
        Path bridge = output.resolve("source/io/micronaut/cdi/mptck/generated/DeploymentBridge.java");
        Files.createDirectories(bridge.getParent());
        String imported = imports.isEmpty() ? "" : "@io.micronaut.context.annotation.ClassImport(classes={"
            + String.join(",", imports.stream().map(s -> s + ".class").toList()) + "})\n";
        Files.writeString(bridge, "package io.micronaut.cdi.mptck.generated;\n" + imported + """
            @jakarta.enterprise.context.Dependent
            public class DeploymentBridge {
                @jakarta.inject.Inject public io.micronaut.context.ApplicationContext context;
                @jakarta.inject.Inject public jakarta.enterprise.context.control.RequestContextController request;
            }
            """);
        files.add(bridge.toFile());
        Path compiled = output.resolve("classes");
        Files.createDirectories(compiled);
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IllegalStateException("A JDK is required to compile TCK deployments");
        boolean success;
        boolean graphql = System.getProperty("mp.tck.component").equals("graphql")
            && System.getProperty("mp.tck.mode").equals("imported");
        if (graphql) {
            try {
                var extension = (jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension)
                    Class.forName("io.micronaut.cdi.mptck.graphql.GraphQlDiscovery").getConstructor().newInstance();
                io.micronaut.cdi.processor.extension.BuildCompatibleExtensionVisitor.overrideExtensions(List.of(extension));
            } catch (ReflectiveOperationException e) { throw new IllegalStateException("Cannot create GraphQL discovery integration", e); }
        }
        try (var manager = compiler.getStandardFileManager(diagnostics, null, null)) {
            var task = compiler.getTask(null, manager, diagnostics,
                List.of("-classpath", System.getProperty("mp.tck.classpath"), "-sourcepath", "",
                    "-d", compiled.toString(), "-s", output.resolve("generated").toString()),
                null, manager.getJavaFileObjectsFromFiles(files));
            // Explicit processor instances share the runtime loader; an isolated processorpath creates
            // conflicting copies of processor visitors and their package-private helpers.
            task.setProcessors(List.of(new io.micronaut.annotation.processing.MixinVisitorProcessor(),
                new io.micronaut.annotation.processing.PackageElementVisitorProcessor(),
                new io.micronaut.annotation.processing.TypeElementVisitorProcessor(),
                new io.micronaut.annotation.processing.AggregatingTypeElementVisitorProcessor(),
                new io.micronaut.annotation.processing.BeanDefinitionInjectProcessor()));
            success = task.call();
        } finally {
            if (graphql) io.micronaut.cdi.processor.extension.BuildCompatibleExtensionVisitor.overrideExtensions(null);
        }
        String report = String.join("\n", diagnostics.getDiagnostics().stream().map(Object::toString).toList());
        Files.writeString(output.resolve("diagnostics.txt"), report);
        Files.writeString(output.resolve("sources.txt"), String.join("\n", files.stream().map(Object::toString).toList()));
        if (!success) throw new jakarta.enterprise.inject.spi.DefinitionException("Deployment compilation rejected:\n" + report);
        return compiled;
    }
}
