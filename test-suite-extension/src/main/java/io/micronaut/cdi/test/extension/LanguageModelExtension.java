
package io.micronaut.cdi.test.extension;

import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.ClassConfig;
import jakarta.enterprise.inject.build.compatible.spi.Enhancement;
import jakarta.enterprise.lang.model.AnnotationInfo;
import jakarta.enterprise.lang.model.declarations.ClassInfo;
import jakarta.enterprise.lang.model.declarations.FieldInfo;
import jakarta.enterprise.lang.model.declarations.MethodInfo;
import jakarta.enterprise.lang.model.types.Type;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Reads the language model of a class marked {@link ModelProbe} and writes what it read onto the class as a
 * {@link ModelReport}, for a test to hold the model to at runtime.
 *
 * <p>The model exists only while the class compiles, so nothing a test asserts can reach it directly; what the
 * kit's language model part verifies with 1263 assertions, this reads a dozen things of, the ones this
 * implementation once got wrong, and reports.</p>
 */
public final class LanguageModelExtension implements BuildCompatibleExtension {

    private static final String TYPE_MARKER = "io.micronaut.cdi.test.extension.TypeMarker";

    @Enhancement(types = Object.class, withSubtypes = true, withAnnotations = ModelProbe.class)
    public void probe(ClassConfig config) {
        ClassInfo info = config.info();
        config.addAnnotation(new ModelReportLiteral(
            names(info.annotations()),
            kinds(info),
            carried(field(info, "tagged").type()),
            carried(info.superClass()),
            info.typeParameters().stream()
                .map(variable -> variable.name() + " extends " + variable.bounds().stream()
                    .map(bound -> bound.asClass().declaration().name()).collect(Collectors.joining(" & ")))
                .collect(Collectors.joining(",")),
            info.constructors().iterator().next().returnType().asClass().declaration().name(),
            members(info),
            field(info, "defaulted").annotations().iterator().next().value().asString(),
            (method(info, "receiving").receiverType().hasAnnotation(a -> a.name().equals(TYPE_MARKER))
                ? "receiver" : "receiver-bare")
                + "," + (method(info, "throwing").throwsTypes().get(0).hasAnnotation(a -> a.name().equals(TYPE_MARKER))
                ? "throws" : "throws-bare"),
            equality(info)));
    }

    private static String names(java.util.Collection<AnnotationInfo> annotations) {
        return annotations.stream().map(annotation -> simple(annotation.name()))
            .collect(Collectors.toCollection(TreeSet::new)).stream().collect(Collectors.joining(","));
    }

    /**
     * The kinds of the nested classes, reached through the fields typed with them: a class has no list of the
     * classes nested in it in the model, and a field's type declaration is the way to one.
     */
    private static String kinds(ClassInfo info) {
        List<String> kinds = new ArrayList<>();
        for (String name : List.of("shape", "point", "tag")) {
            ClassInfo nested = field(info, name).type().asClass().declaration();
            List<String> kind = new ArrayList<>();
            if (nested.isEnum()) {
                kind.add("enum");
            }
            if (nested.isRecord()) {
                kind.add("record");
            }
            if (nested.isAnnotation()) {
                kind.add("annotation");
            }
            if (nested.isPlainClass()) {
                kind.add("class");
            }
            if (nested.isAbstract()) {
                kind.add("abstract");
            }
            kinds.add(name + ":" + String.join(",", kind));
        }
        return String.join(";", kinds);
    }

    private static String carried(Type type) {
        Predicate<AnnotationInfo> marker = annotation -> annotation.name().equals(TYPE_MARKER);
        String annotated = type.hasAnnotation(marker) ? "annotated" : "bare";
        Type argument = type.asParameterizedType().typeArguments().get(0);
        return annotated + "," + (argument.hasAnnotation(marker) ? "argument-annotated" : "argument-bare");
    }

    /**
     * The methods and fields, as the model lists them: inherited ones included, and a bridge method not.
     */
    private static String members(ClassInfo info) {
        List<String> names = new ArrayList<>();
        for (MethodInfo method : info.methods()) {
            names.add(method.name());
        }
        for (FieldInfo field : info.fields()) {
            names.add(field.name());
        }
        return names.stream().sorted().collect(Collectors.joining(","));
    }

    private static String equality(ClassInfo info) {
        List<String> equal = new ArrayList<>();
        if (field(info, "first").type().equals(field(info, "second").type())) {
            equal.add("primitives");
        }
        if (field(info, "self").type().asParameterizedType().declaration().equals(info)) {
            equal.add("declarations");
        }
        if (field(info, "tagged").type().equals(field(info, "taggedAgain").type())) {
            equal.add("parameterized");
        }
        return String.join(",", equal);
    }

    private static FieldInfo field(ClassInfo info, String name) {
        return info.fields().stream().filter(field -> field.name().equals(name)).findFirst()
            .orElseThrow(() -> new IllegalStateException("No field " + name + " in " + info.name()));
    }

    private static MethodInfo method(ClassInfo info, String name) {
        return info.methods().stream().filter(method -> method.name().equals(name)).findFirst()
            .orElseThrow(() -> new IllegalStateException("No method " + name + " in " + info.name()));
    }

    private static String simple(String name) {
        return name.substring(Math.max(name.lastIndexOf('.'), name.lastIndexOf('$')) + 1);
    }
}
