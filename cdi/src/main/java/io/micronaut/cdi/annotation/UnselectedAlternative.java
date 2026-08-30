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
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;

import java.util.Set;

/**
 * The condition of an alternative that no priority selected: it holds where the SE bootstrap selected the
 * alternative as the container was built, and nowhere else.
 *
 * <p>Section 2.1.7 enables an alternative by a priority as it is compiled. The SE bootstrap of the
 * specification adds a second moment — {@code SeContainerInitializer.selectAlternatives} and
 * {@code selectAlternativeStereotypes} — and what they selected is put where every condition can read it: the
 * properties this condition consults. The names that would select this bean were written beside it as
 * {@link CdiSelectableAlternative} when it was compiled.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class UnselectedAlternative implements Condition {

    /**
     * The property naming the alternative classes the SE bootstrap selected, comma separated.
     */
    public static final String SELECTED_CLASSES = "io.micronaut.cdi.alternatives.classes";

    /**
     * The property naming the alternative stereotypes the SE bootstrap selected, comma separated.
     */
    public static final String SELECTED_STEREOTYPES = "io.micronaut.cdi.alternatives.stereotypes";

    @Override
    public boolean matches(ConditionContext context) {
        AnnotationValue<CdiSelectableAlternative> selectable = context.getComponent().getAnnotationMetadata()
            .getAnnotation(CdiSelectableAlternative.class);
        if (selectable == null) {
            return false;
        }
        boolean producer = selectable.booleanValue("producer").orElse(false);
        if (!producer && context.getComponent().getAnnotationMetadata()
            .hasAnnotation("io.micronaut.cdi.annotation.CdiProducer")) {
            // the selection is about the class being a bean; what a producer elsewhere makes of the class is a
            // bean of the producer's, the same way a vetoed class may still be produced
            return true;
        }
        Set<String> classes = selected(context, SELECTED_CLASSES);
        if (selectable.stringValue().map(classes::contains).orElse(false)) {
            return true;
        }
        Set<String> stereotypes = selected(context, SELECTED_STEREOTYPES);
        for (String stereotype : selectable.stringValues("stereotypes")) {
            if (stereotypes.contains(stereotype)) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> selected(ConditionContext<?> context, String property) {
        if (!(context.getBeanContext() instanceof io.micronaut.context.ApplicationContext application)) {
            return Set.of();
        }
        String names = application.getProperty(property, String.class).orElse("");
        if (names.isEmpty()) {
            return Set.of();
        }
        return Set.of(names.split(","));
    }
}
