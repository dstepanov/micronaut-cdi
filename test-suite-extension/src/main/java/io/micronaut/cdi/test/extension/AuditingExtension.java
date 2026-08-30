package io.micronaut.cdi.test.extension;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.ClassConfig;
import jakarta.enterprise.inject.build.compatible.spi.Enhancement;
import jakarta.enterprise.inject.build.compatible.spi.FieldConfig;
import jakarta.enterprise.inject.build.compatible.spi.Messages;
import jakarta.inject.Inject;

/**
 * An extension that makes every audited class a bean, and every field it marks an injection point.
 */
public final class AuditingExtension implements BuildCompatibleExtension {

    /**
     * Makes an audited class an application scoped bean, which it does not say itself.
     *
     * @param config   The class
     * @param messages What the extension has to say
     */
    @Enhancement(types = Object.class, withSubtypes = true, withAnnotations = Audited.class)
    public void auditedClassesAreBeans(ClassConfig config, Messages messages) {
        messages.info("making " + config.info().simpleName() + " an application scoped bean");
        config.addAnnotation(ApplicationScoped.class);
    }

    /**
     * Records every audited bean the container was given, as it is compiled.
     *
     * @param bean     The bean
     * @param messages What the extension has to say
     */
    @jakarta.enterprise.inject.build.compatible.spi.Registration(types = AuditLog.class)
    public void theAuditLogIsRegistered(
        jakarta.enterprise.inject.build.compatible.spi.BeanInfo bean,
        Messages messages) {
        REGISTERED.add(bean.types().size() + " types, scope " + bean.scope().name());
    }

    /**
     * What the registration phase was told, for a test to read back.
     */
    public static final java.util.List<String> REGISTERED =
        java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    /**
     * Adds a bean no class declares, created from what is attached to it here.
     *
     * @param components What the extension adds to the container
     */
    @jakarta.enterprise.inject.build.compatible.spi.Synthesis
    public void theAuditLogIsABean(
        jakarta.enterprise.inject.build.compatible.spi.SyntheticComponents components) {
        components.addBean(AuditLog.class)
            .type(AuditLog.class)
            .scope(ApplicationScoped.class)
            .withParam("name", "the audit log")
            .createWith(AuditLogCreator.class);
    }

    /**
     * Makes a field marked for it an injection point.
     *
     * @param config The field
     */
    @Enhancement(types = Object.class, withSubtypes = true, withAnnotations = Audited.class)
    public void markedFieldsAreInjected(FieldConfig config) {
        if (config.info().hasAnnotation(AutoInject.class)) {
            config.addAnnotation(Inject.class);
        }
    }
}
