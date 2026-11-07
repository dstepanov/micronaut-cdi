package io.micronaut.cdi.test.extension.kept;

import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.build.compatible.spi.Parameters;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticBeanCreator;

/**
 * What {@link KeptDescriptionsExtension} read in the synthesis phase of the descriptions it kept from the
 * registration phase, as a synthetic bean a test reads at runtime.
 *
 * @param text What it read
 */
public record KeptDescriptionReport(String text) {

    /**
     * Creates the report from the parameter the extension attached.
     */
    public static final class Creator implements SyntheticBeanCreator<KeptDescriptionReport> {

        @Override
        public KeptDescriptionReport create(Instance<Object> lookup, Parameters params) {
            return new KeptDescriptionReport(params.get("text", String.class));
        }
    }
}
