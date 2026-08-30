package io.micronaut.cdi.test.extension;

import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.ClassConfig;
import jakarta.enterprise.inject.build.compatible.spi.Discovery;
import jakarta.enterprise.inject.build.compatible.spi.Enhancement;
import jakarta.enterprise.inject.build.compatible.spi.ScannedClasses;

/**
 * Scans a class that says nothing on its own, and takes a qualifier off another: what the discovery and
 * enhancement phases of section 2.10 let an extension do.
 */
public class ScanAndChangeExtension implements BuildCompatibleExtension {

    @Discovery
    public void scan(ScannedClasses scan) {
        scan.add("io.micronaut.cdi.test.PlainScannedBean");
    }

    @Enhancement(types = Object.class, withSubtypes = true, withAnnotations = RemovableQualifier.class)
    public void strip(ClassConfig config) {
        config.removeAnnotation(annotation ->
            annotation.name().equals(RemovableQualifier.class.getName()));
    }

    @Enhancement(types = Object.class, withSubtypes = true, withAnnotations = MarkTheField.class)
    public void silence(jakarta.enterprise.inject.build.compatible.spi.MethodConfig method) {
        if ("silenced".equals(method.info().name())) {
            method.parameters().get(0).removeAllAnnotations();
        }
    }

    @Enhancement(types = Object.class, withSubtypes = true, withAnnotations = MarkTheField.class)
    public void mark(jakarta.enterprise.inject.build.compatible.spi.FieldConfig field) {
        if (field.info().hasAnnotation(MarkTheField.class)) {
            field.addAnnotation(AddedQualifier.class);
        }
    }
}
