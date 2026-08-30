package io.micronaut.cdi.test.extension;

/**
 * A bean no class declares: the extension describes it and the container creates it.
 */
public final class AuditLog {

    private final String name;

    AuditLog(String name) {
        this.name = name;
    }

    public String name() {
        return name;
    }
}
