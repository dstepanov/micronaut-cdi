
package io.micronaut.cdi.test.extension;

import jakarta.enterprise.util.AnnotationLiteral;

/**
 * A {@link ModelReport} made at compile time, for the extension to add to a class.
 */
@SuppressWarnings("serial")
final class ModelReportLiteral extends AnnotationLiteral<ModelReport> implements ModelReport {

    private final String annotations;
    private final String kinds;
    private final String fieldType;
    private final String superType;
    private final String typeParameters;
    private final String constructorReturn;
    private final String members;
    private final String defaults;
    private final String receiverAndThrows;
    private final String equality;

    ModelReportLiteral(String annotations, String kinds, String fieldType, String superType, String typeParameters,
                       String constructorReturn, String members, String defaults, String receiverAndThrows,
                       String equality) {
        this.annotations = annotations;
        this.kinds = kinds;
        this.fieldType = fieldType;
        this.superType = superType;
        this.typeParameters = typeParameters;
        this.constructorReturn = constructorReturn;
        this.members = members;
        this.defaults = defaults;
        this.receiverAndThrows = receiverAndThrows;
        this.equality = equality;
    }

    @Override
    public String annotations() {
        return annotations;
    }

    @Override
    public String kinds() {
        return kinds;
    }

    @Override
    public String fieldType() {
        return fieldType;
    }

    @Override
    public String superType() {
        return superType;
    }

    @Override
    public String typeParameters() {
        return typeParameters;
    }

    @Override
    public String constructorReturn() {
        return constructorReturn;
    }

    @Override
    public String members() {
        return members;
    }

    @Override
    public String defaults() {
        return defaults;
    }

    @Override
    public String receiverAndThrows() {
        return receiverAndThrows;
    }

    @Override
    public String equality() {
        return equality;
    }
}
