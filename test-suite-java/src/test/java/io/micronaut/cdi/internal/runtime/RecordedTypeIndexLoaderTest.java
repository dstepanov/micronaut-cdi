package io.micronaut.cdi.internal.runtime;

import io.micronaut.context.ApplicationContext;
import io.micronaut.core.type.Argument;
import io.micronaut.core.type.TypeHierarchies;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The type hierarchy record of a class is what the class loader of the container finds, and is kept by that
 * container alone: two containers whose loaders see different records of one class both loaded by a loader they
 * share each read their own, whichever was asked first, and a record found through a deployment's loader is not
 * kept with the shared class.
 */
public class RecordedTypeIndexLoaderTest {

    /**
     * A class of the loader the deployments share, which the compilation of this suite records nothing of: a
     * deployment's records say what it is.
     */
    public static final class SharedPayload {
    }

    @Test
    void eachContainerReadsTheRecordItsLoaderFinds(@TempDir Path directory) throws Exception {
        try (URLClassLoader first = deployment(directory.resolve("first"), "first", String.class);
             URLClassLoader second = deployment(directory.resolve("second"), "second", Integer.class)) {
            assertEquals(String.class, recordedArgument(first));
            assertEquals(Integer.class, recordedArgument(second));
        } finally {
            TypeHierarchies.invalidate();
        }
    }

    /**
     * The argument of {@code Comparable} the record the container of the given loader finds says the shared class
     * implements, asked with that loader as the context loader of the thread, as a deployment runs.
     */
    private static Class<?> recordedArgument(ClassLoader loader) {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(loader);
        try (ApplicationContext context = ApplicationContext.builder().classLoader(loader).start()) {
            RecordedTypeIndex.Entry entry = RecordedTypeIndex.of(context).of(SharedPayload.class);
            assertNotNull(entry, "the record the deployment's loader sees is found");
            RecordedTypeIndex.HierarchyEntry hierarchy = assertInstanceOf(RecordedTypeIndex.HierarchyEntry.class, entry);
            assertEquals(SharedPayload.class, hierarchy.hierarchy().getType());
            List<Argument<?>> closure = entry.closureOf(Argument.of(SharedPayload.class));
            assertNotNull(closure);
            return closure.stream().filter(type -> type.getType() == Comparable.class)
                .findFirst().orElseThrow().getTypeParameters()[0].getType();
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    /**
     * A deployment's loader that sees a record of the shared class, relocated into a package of its own, saying
     * that it implements {@code Comparable} of the given argument.
     */
    private static URLClassLoader deployment(Path root, String recordPackage, Class<?> argument) throws IOException {
        String typeName = SharedPayload.class.getName();
        String recordName = TypeHierarchies.recordClassName(typeName, recordPackage);
        int lastDot = recordName.lastIndexOf('.');
        String packageName = recordName.substring(0, lastDot);
        String simpleName = recordName.substring(lastDot + 1);
        String comparable = "io.micronaut.core.type.Argument.of(java.lang.Comparable.class, " + argument.getName() + ".class)";
        String source = "package " + packageName + ";\n"
            + "public final class " + simpleName + " extends io.micronaut.core.type.GeneratedTypeHierarchy {\n"
            + "    public " + simpleName + "() {\n"
            + "        super(" + SharedPayload.class.getCanonicalName() + ".class, null, null,\n"
            + "            new io.micronaut.core.type.Argument<?>[] {" + comparable + "},\n"
            + "            new io.micronaut.core.type.Argument<?>[] {" + comparable + ", io.micronaut.core.type.Argument.OBJECT_ARGUMENT});\n"
            + "    }\n"
            + "}\n";
        Path sourceFile = root.resolve("src").resolve(simpleName + ".java");
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, source);
        Path classes = root.resolve("classes");
        Files.createDirectories(classes);
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        int status = compiler.run(null, null, null, "-proc:none", "-classpath", System.getProperty("java.class.path"),
            "-d", classes.toString(), sourceFile.toString());
        assertEquals(0, status, "the record compiles");
        Path entry = classes.resolve("META-INF/micronaut/io.micronaut.core.type.TypeHierarchy").resolve(recordName);
        Files.createDirectories(entry.getParent());
        Files.writeString(entry, "");
        return new URLClassLoader(new URL[] {classes.toUri().toURL()}, RecordedTypeIndexLoaderTest.class.getClassLoader());
    }
}
