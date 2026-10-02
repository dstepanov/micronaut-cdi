/* Licensed under the Apache License, Version 2.0. */
package io.micronaut.cdi.mptck.configlite;

import io.smallrye.config.ConfigMappings;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.inject.ConfigProducerUtil;
import jakarta.enterprise.inject.build.compatible.spi.Parameters;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticObserver;
import jakarta.enterprise.inject.spi.DeploymentException;
import jakarta.enterprise.inject.spi.EventContext;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.lang.reflect.*;
import java.util.*;

/** Validates runtime values at startup; compilation does not read deployment configuration. */
public final class ConfigDeploymentValidator implements SyntheticObserver<Object> {
    @Override
    public void observe(EventContext<Object> event, Parameters parameters) {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        SmallRyeConfig config = io.smallrye.config.Config.getOrCreate(loader).unwrap(SmallRyeConfig.class);
        List<Throwable> failures = new ArrayList<>();
        Set<ConfigMappings.ConfigClass> mappings = new HashSet<>();
        for (String mapping : parameters.get("mappings", String[].class)) {
            int split = mapping.indexOf('#');
            try {
                mappings.add(ConfigMappings.ConfigClass.configClass(
                    Class.forName(mapping.substring(0, split), false, loader), mapping.substring(split + 1)));
            } catch (ReflectiveOperationException failure) { failures.add(failure); }
        }
        try { ConfigMappings.registerConfigClasses(config, mappings, false); }
        catch (RuntimeException failure) { failures.add(failure); }
        for (String request : parameters.get("requests", String[].class)) {
            try { validate(request, loader, config); }
            catch (ReflectiveOperationException | RuntimeException failure) {
                failures.add(new IllegalArgumentException("Invalid configuration for " + request, failure));
            }
        }
        if (!failures.isEmpty()) {
            DeploymentException rejection = new DeploymentException("SmallRye Config deployment validation failed");
            failures.forEach(rejection::addSuppressed);
            throw rejection;
        }
    }

    private static void validate(String request, ClassLoader loader, SmallRyeConfig config)
        throws ReflectiveOperationException {
        int ownerEnd = request.indexOf('#');
        Class<?> owner = Class.forName(request.substring(0, ownerEnd), false, loader);
        String member = request.substring(ownerEnd + 1);
        Type type;
        ConfigProperty property;
        String defaultName;
        int signatureStart = member.indexOf('(');
        if (signatureStart < 0) {
            Field field = field(owner, member);
            type = field.getGenericType();
            property = field.getAnnotation(ConfigProperty.class);
            defaultName = field.getDeclaringClass().getCanonicalName() + "." + field.getName();
        } else {
            int signatureEnd = member.indexOf(')');
            String arguments = member.substring(signatureStart + 1, signatureEnd);
            Class<?>[] types = arguments.isEmpty() ? new Class<?>[0] : Arrays.stream(arguments.split(","))
                .map(name -> load(name, loader)).toArray(Class<?>[]::new);
            Executable executable = member.startsWith("<init>(") ? owner.getDeclaredConstructor(types)
                : method(owner, member.substring(0, signatureStart), types);
            Parameter parameter = executable.getParameters()[Integer.parseInt(member.substring(signatureEnd + 2))];
            type = parameter.getParameterizedType();
            property = parameter.getAnnotation(ConfigProperty.class);
            defaultName = executable.getDeclaringClass().getCanonicalName() + "." + parameter.getName();
        }
        if (property == null) throw new IllegalArgumentException("Configuration annotation missing for " + request);
        if (isDeferredOrOptional(type)) return;
        if (type instanceof ParameterizedType parameterized
            && parameterized.getRawType().equals(jakarta.enterprise.inject.Instance.class)) {
            type = parameterized.getActualTypeArguments()[0];
        }
        String name = property.name().isEmpty() ? defaultName : property.name();
        String defaultValue = property.defaultValue().equals(ConfigProperty.UNCONFIGURED_VALUE)
            ? null : property.defaultValue();
        ConfigProducerUtil.getValue(name, type, defaultValue, config);
    }

    private static Field field(Class<?> owner, String name) throws NoSuchFieldException {
        for (Class<?> type = owner; type != null; type = type.getSuperclass()) {
            try { return type.getDeclaredField(name); }
            catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(owner.getName() + "." + name);
    }

    private static Method method(Class<?> owner, String name, Class<?>[] parameters) throws NoSuchMethodException {
        for (Class<?> type = owner; type != null; type = type.getSuperclass()) {
            try { return type.getDeclaredMethod(name, parameters); }
            catch (NoSuchMethodException ignored) { }
        }
        throw new NoSuchMethodException(owner.getName() + "." + name);
    }

    private static boolean isDeferredOrOptional(Type type) {
        if (type == OptionalInt.class || type == OptionalLong.class || type == OptionalDouble.class
            || type == org.eclipse.microprofile.config.ConfigValue.class || type == io.smallrye.config.ConfigValue.class) return true;
        if (type instanceof ParameterizedType parameterized) {
            Type raw = parameterized.getRawType();
            return raw == Optional.class || raw == jakarta.inject.Provider.class || raw == java.util.function.Supplier.class;
        }
        return false;
    }

    private static Class<?> load(String name, ClassLoader loader) {
        if (name.endsWith("[]")) return Array.newInstance(load(name.substring(0, name.length() - 2), loader), 0).getClass();
        return switch (name) {
            case "boolean" -> boolean.class;
            case "byte" -> byte.class;
            case "short" -> short.class;
            case "int" -> int.class;
            case "long" -> long.class;
            case "float" -> float.class;
            case "double" -> double.class;
            case "char" -> char.class;
            default -> {
                try { yield Class.forName(name, false, loader); }
                catch (ClassNotFoundException failure) { throw new IllegalArgumentException("Missing configuration type " + name, failure); }
            }
        };
    }
}
