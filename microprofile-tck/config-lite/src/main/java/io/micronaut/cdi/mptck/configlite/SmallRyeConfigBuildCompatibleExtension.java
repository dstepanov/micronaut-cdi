/* Licensed under the Apache License, Version 2.0. */
package io.micronaut.cdi.mptck.configlite;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.context.Initialized;
import jakarta.enterprise.inject.Vetoed;
import jakarta.enterprise.inject.build.compatible.spi.*;
import jakarta.enterprise.lang.model.AnnotationInfo;
import jakarta.enterprise.lang.model.declarations.ClassInfo;
import jakarta.enterprise.lang.model.declarations.DeclarationInfo;
import jakarta.enterprise.lang.model.types.Type;
import jakarta.enterprise.util.Nonbinding;
import org.eclipse.microprofile.config.inject.ConfigProperties;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.*;

/** Records SmallRye discovery metadata during compilation, without portable extension callbacks. */
public final class SmallRyeConfigBuildCompatibleExtension implements BuildCompatibleExtension {
    private static final Set<String> PRODUCED = Set.of("java.lang.String", "java.lang.Boolean", "java.lang.Byte",
        "java.lang.Short", "java.lang.Integer", "java.lang.Long", "java.lang.Float", "java.lang.Double",
        "java.lang.Character", "java.util.OptionalInt", "java.util.OptionalLong", "java.util.OptionalDouble",
        "io.smallrye.config.ConfigValue", "org.eclipse.microprofile.config.ConfigValue");
    private final Map<String, Type> customTypes = new TreeMap<>();
    private final Map<String, ClassInfo> propertyClasses = new TreeMap<>();
    private final Map<String, String> defaultPrefixes = new TreeMap<>();
    private final Map<String, Set<String>> requestedPrefixes = new TreeMap<>();
    private final Set<String> requests = new TreeSet<>();

    @Discovery
    public void discover(ScannedClasses classes) {
        classes.add("io.smallrye.config.inject.ConfigProducer");
        classes.add(ConfigProperties.class.getName());
    }

    @Enhancement(types = ConfigProperties.class)
    public void propertiesQualifier(MethodConfig method) {
        // Micronaut maps CDI Nonbinding to its native annotation while reading the dependency.
        method.removeAnnotation(annotation -> annotation.name().equals(Nonbinding.class.getName())
            || annotation.name().equals("io.micronaut.context.annotation.NonBinding"));
    }

    @Enhancement(types = Object.class, withSubtypes = true)
    public void collect(ClassConfig bean) {
        ClassInfo type = bean.info();
        AnnotationInfo properties = type.annotation(ConfigProperties.class);
        if (properties != null) {
            propertyClasses.put(type.name(), type);
            defaultPrefixes.put(type.name(), prefix(properties));
            requestedPrefixes.computeIfAbsent(type.name(), ignored -> new TreeSet<>()).add(prefix(properties));
            bean.addAnnotation(Vetoed.class);
        }
        for (FieldConfig field : bean.fields()) {
            if (!field.info().isStatic() && field.info().hasAnnotation(jakarta.inject.Inject.class)) {
                configureProperties(field);
                collect(field.info(), field.info().type(), type.name() + "#" + field.info().name());
            }
        }
        List<MethodConfig> methods = new ArrayList<>(bean.constructors());
        methods.addAll(bean.methods());
        for (MethodConfig method : methods) {
            if (!method.info().hasAnnotation(jakarta.inject.Inject.class)
                && !method.info().hasAnnotation(jakarta.enterprise.inject.Produces.class)
                && method.parameters().stream().noneMatch(p -> p.info().hasAnnotation(jakarta.enterprise.event.Observes.class)
                    || p.info().hasAnnotation(jakarta.enterprise.event.ObservesAsync.class))) continue;
            if (method.parameters().stream().noneMatch(p -> p.info().hasAnnotation(ConfigProperty.class)
                || p.info().hasAnnotation(ConfigProperties.class))) continue;
            int index = 0;
            String signature = String.join(",", method.info().parameters().stream().map(p -> descriptor(p.type())).toList());
            for (ParameterConfig parameter : method.parameters()) {
                configureProperties(parameter);
                collect(parameter.info(), parameter.info().type(), type.name() + "#"
                    + (method.info().isConstructor() ? "<init>" : method.info().name()) + "(" + signature + ")#" + index++);
            }
        }
    }

    private static void configureProperties(DeclarationConfig point) {
        if (point.info().hasAnnotation(ConfigProperties.class)) {
            point.addAnnotation(AnnotationBuilder.of(io.micronaut.context.annotation.ResolveWith.class)
                .value(io.micronaut.cdi.internal.runtime.CdiBeanInjectionProvider.class).build());
        }
    }

    private void collect(DeclarationInfo point, Type type, String member) {
        if (point.hasAnnotation(ConfigProperty.class)) {
            requests.add(member);
            Type requested = type;
            if (requested.isParameterizedType()) {
                String raw = requested.asParameterizedType().genericClass().declaration().name();
                if (raw.equals("jakarta.inject.Provider") || raw.equals("jakarta.enterprise.inject.Instance")) {
                    requested = requested.asParameterizedType().typeArguments().get(0);
                } else return;
            }
            if (requested.isArray() || requested.isClass() && !PRODUCED.contains(requested.asClass().declaration().name())) {
                customTypes.put(descriptor(requested), requested);
            }
        }
        AnnotationInfo properties = point.annotation(ConfigProperties.class);
        if (properties != null && type.isClass()) {
            requestedPrefixes.computeIfAbsent(type.asClass().declaration().name(), ignored -> new TreeSet<>())
                .add(properties.member("prefix").asString());
        }
    }

    @Synthesis
    public void synthesise(SyntheticComponents components) {
        for (Type type : customTypes.values()) {
            components.addBean(Object.class).type(type).qualifier(ConfigProperty.class)
                .scope(Dependent.class).createWith(ConfigValueCreator.class);
        }
        List<String> mappings = new ArrayList<>();
        for (var entry : propertyClasses.entrySet()) {
            String type = entry.getKey();
            String defaultPrefix = defaultPrefixes.get(type);
            for (String requested : requestedPrefixes.get(type)) {
                String actual = requested.equals(ConfigProperties.UNCONFIGURED_PREFIX) ? defaultPrefix : requested;
                mappings.add(type + "#" + actual);
                AnnotationInfo qualifier = AnnotationBuilder.of(ConfigProperties.class).member("prefix", requested).build();
                components.addBean(Object.class).type(entry.getValue()).qualifier(qualifier).scope(Dependent.class)
                    .withParam("class", entry.getValue()).withParam("prefix", actual)
                    .createWith(ConfigPropertiesCreator.class);
            }
        }
        components.addObserver(Object.class).qualifier(Initialized.Literal.of(ApplicationScoped.class))
            .withParam("requests", requests.toArray(String[]::new))
            .withParam("mappings", mappings.stream().distinct().toArray(String[]::new))
            .observeWith(ConfigDeploymentValidator.class);
    }

    private static String prefix(AnnotationInfo annotation) {
        String prefix = annotation.member("prefix").asString();
        return prefix.equals(ConfigProperties.UNCONFIGURED_PREFIX) ? "" : prefix;
    }

    static String descriptor(Type type) {
        if (type.isArray()) return descriptor(type.asArray().componentType()) + "[]";
        if (type.isClass()) return type.asClass().declaration().name();
        if (type.isPrimitive()) return type.asPrimitive().primitiveKind().name().toLowerCase(Locale.ROOT);
        if (type.isParameterizedType()) return type.asParameterizedType().genericClass().declaration().name();
        throw new IllegalArgumentException("Unsupported configuration type " + type);
    }
}
