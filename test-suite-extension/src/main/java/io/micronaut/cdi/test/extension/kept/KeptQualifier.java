package io.micronaut.cdi.test.extension.kept;

import jakarta.enterprise.util.AnnotationLiteral;
import jakarta.inject.Qualifier;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * The qualifier {@link KeptDescriptionsExtension} adds to a class marked {@link KeptDescription} as it is enhanced,
 * which the registration phase has to see on its bean.
 */
@Qualifier
@Retention(RetentionPolicy.RUNTIME)
public @interface KeptQualifier {

    /**
     * The qualifier as an instance, which is what an extension adds to a class.
     */
    final class Literal extends AnnotationLiteral<KeptQualifier> implements KeptQualifier {

        /**
         * The instance.
         */
        public static final Literal INSTANCE = new Literal();

        private static final long serialVersionUID = 1L;
    }
}
