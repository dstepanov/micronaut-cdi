/*
 * Copyright 2017-2026 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.cdi.lang.model.ast;

import io.micronaut.core.annotation.Internal;
import io.micronaut.inject.ast.FieldElement;
import io.micronaut.inject.ast.MethodElement;
import io.micronaut.inject.ast.PropertyElement;
import io.micronaut.inject.visitor.VisitorContext;
import jakarta.enterprise.lang.model.declarations.ClassInfo;
import jakarta.enterprise.lang.model.declarations.FieldInfo;
import jakarta.enterprise.lang.model.declarations.MethodInfo;
import jakarta.enterprise.lang.model.declarations.RecordComponentInfo;
import jakarta.enterprise.lang.model.types.Type;

/**
 * A component of a record, read from the property Micronaut describes the component as: its field and its accessor
 * are the ones the property names, and its annotations are what the property carries, which are the component's
 * together with those of its field and its accessor (Micronaut merges the three).
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class ElementRecordComponentInfo extends ElementDeclarationInfo implements RecordComponentInfo {

    private final PropertyElement element;
    private final FieldElement field;
    private final MethodElement accessor;
    private final ClassInfo declaringRecord;

    /**
     * @param element         The property the component is described as
     * @param field           The field of the component
     * @param accessor        The accessor of the component
     * @param declaringRecord The record that declares it
     * @param context         The compilation the component is read in
     */
    public ElementRecordComponentInfo(PropertyElement element, FieldElement field, MethodElement accessor,
                                      ClassInfo declaringRecord, VisitorContext context) {
        super(element, context);
        this.element = element;
        this.field = field;
        this.accessor = accessor;
        this.declaringRecord = declaringRecord;
    }

    @Override
    public String name() {
        return element.getName();
    }

    @Override
    public Type type() {
        return SourceModel.of(context()).typeOf(field);
    }

    @Override
    public FieldInfo field() {
        return new ElementFieldInfo(field, declaringRecord, context());
    }

    @Override
    public MethodInfo accessor() {
        return new ElementMethodInfo(accessor, declaringRecord, context());
    }

    @Override
    public ClassInfo declaringRecord() {
        return declaringRecord;
    }
}
