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
package io.micronaut.cdi.annotation;

import io.micronaut.context.condition.Condition;
import io.micronaut.context.condition.ConditionContext;
import io.micronaut.core.annotation.Internal;

/**
 * The condition of a class that the specification says is not a bean.
 *
 * <p>A class is taken out of the set of beans by a condition rather than by taking the annotations that made it
 * one off it: Micronaut decides what to generate a bean definition for from the class as it was written, so a
 * class that is annotated as a bean has a definition whatever is done to its annotations afterwards. A definition
 * whose condition never holds is never a candidate for anything, which is what a class that is not a bean
 * amounts to.</p>
 *
 * <p>It is what {@code jakarta.enterprise.inject.Vetoed} is read as, and what an alternative that has not been
 * selected is read as.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class NotABean implements Condition {

    @Override
    public boolean matches(ConditionContext context) {
        // a bean produced from the class carries the class's annotations, this condition among them, but the
        // veto is on the class being a bean — what a producer makes of it is a bean of the producer's
        return context.getComponent().getAnnotationMetadata()
            .hasAnnotation("io.micronaut.cdi.annotation.CdiProducer");
    }
}
