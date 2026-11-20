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
package io.micronaut.cdi.internal.runtime;

import io.micronaut.cdi.internal.metadata.CdiInheritedProducers;
import io.micronaut.cdi.internal.metadata.CdiProducer;
import io.micronaut.context.condition.Condition;
import io.micronaut.context.condition.ConditionContext;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;

/**
 * The condition of a class that inherits producers, which takes out of it the products Micronaut builds of
 * those producers under it ({@link CdiInheritedProducers}): a producer is no member a subclass inherits (CDI 4.1 section
 * 4.2), and its product is the one the class declaring it produces. A definition whose condition never holds is never
 * a candidate for anything, so the products are no beans, no candidates of a lookup of Micronaut's, and are never
 * created. The class itself and the products of its own producers are left as they are.
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class InheritedProducer implements Condition {

    @Override
    public boolean matches(ConditionContext context) {
        AnnotationMetadata metadata = context.getComponent().getAnnotationMetadata();
        // the processor records the producer on every inherited producer as it is read through the class inheriting
        // it, so a product Micronaut builds of it under that class carries the record too
        AnnotationValue<CdiProducer> producer = metadata.getAnnotation(CdiProducer.class);
        if (producer == null) {
            return true;
        }
        String produced = producer.stringValue("declaringType").orElse("") + "#" + producer.stringValue("member").orElse("");
        for (String inherited : metadata.stringValues(CdiInheritedProducers.class)) {
            if (inherited.equals(produced)) {
                return false;
            }
        }
        return true;
    }
}
