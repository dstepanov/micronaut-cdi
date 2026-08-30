package io.micronaut.cdi.test.extension;

import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.build.compatible.spi.Parameters;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticBeanCreator;

/**
 * Creates the audit log the extension described, from what the extension attached to it.
 */
public final class AuditLogCreator implements SyntheticBeanCreator<AuditLog> {

    @Override
    public AuditLog create(Instance<Object> lookup, Parameters params) {
        return new AuditLog(params.get("name", String.class));
    }
}
