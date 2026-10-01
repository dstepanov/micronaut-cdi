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

import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.Element;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.ServiceLoader;

/**
 * Decides which of the annotations Micronaut recorded on a declaration or on a use of a type are reported to
 * a build compatible extension through the language model.
 *
 * <p>Micronaut records more than the source wrote: what its mappers and remappers add, the annotations it
 * writes itself, the container a repeatable annotation is folded into. By default the model reports all of
 * it, retained-until-runtime ones only. A filter registered as a service of this interface narrows that to
 * whatever its deployment expects, the way the technology compatibility kit's own filter narrows it to what
 * the specification's language model would report of the source; every registered filter must agree for an
 * annotation to be reported.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
public interface LanguageModelAnnotationFilter {

    /**
     * Whether an annotation is reported.
     *
     * @param annotationName The annotation interface's binary name
     * @param place          Where the annotation was recorded
     * @return Whether it is reported
     */
    boolean isReported(String annotationName, Place place);

    /**
     * The registered filters, loaded once.
     *
     * @return The filters, possibly none
     */
    static List<LanguageModelAnnotationFilter> registered() {
        return Registered.FILTERS;
    }

    /**
     * Where an annotation was recorded: on a declaration, or on a use of a type within one.
     *
     * @param declaration   The declaration carrying the annotation, or {@code null} for a use of a type
     * @param declaringType The class the declaration belongs to, or the class itself; {@code null} where unknown
     * @param onType        Whether the annotation is on a use of a type rather than on the declaration
     */
    record Place(@Nullable Element declaration, @Nullable ClassElement declaringType, boolean onType) {
    }

    /**
     * Holds the loaded filters.
     */
    final class Registered {

        private static final List<LanguageModelAnnotationFilter> FILTERS = ServiceLoader
            .load(LanguageModelAnnotationFilter.class, LanguageModelAnnotationFilter.class.getClassLoader())
            .stream().map(ServiceLoader.Provider::get).toList();

        private Registered() {
        }
    }
}
