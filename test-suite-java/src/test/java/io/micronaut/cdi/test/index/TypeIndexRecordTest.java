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
package io.micronaut.cdi.test.index;

import io.micronaut.cdi.internal.metadata.CdiTypeIndex;
import io.micronaut.context.ApplicationContext;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.inject.BeanDefinition;
import io.micronaut.inject.qualifiers.Qualifiers;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The generic hierarchy of a class no definition describes is recorded on a definition of the compilation, placed
 * where the classes it refers to can be referred to: a package-private supertype from its own package.
 */
class TypeIndexRecordTest {

    @Test
    void aPackagePrivateSupertypeIsRecordedAsItsClass() {
        try (ApplicationContext context = ApplicationContext.run()) {
            AnnotationValue<Annotation> entry = entryOf(context,
                "io.micronaut.cdi.test.index.hidden.HiddenPayload");
            assertNotNull(entry, "the payload is recorded");
            List<AnnotationValue<Annotation>> supertypes = entry.getAnnotations("supertypes");
            assertEquals("io.micronaut.cdi.test.index.hidden.HiddenBase",
                supertypes.get(0).stringValue("value").orElse(null));
            assertTrue(supertypes.get(0).classValue("value").isPresent(),
                "the package-private supertype is recorded as a class, from its own package");
        }
    }

    static AnnotationValue<Annotation> entryOf(ApplicationContext context, String className) {
        for (BeanDefinition<?> definition : context.getBeanDefinitions(Qualifiers.byStereotype(CdiTypeIndex.class))) {
            AnnotationValue<CdiTypeIndex> index = definition.getAnnotationMetadata().getAnnotation(CdiTypeIndex.class);
            if (index == null) {
                continue;
            }
            for (AnnotationValue<Annotation> entry : index.getAnnotations("value")) {
                if (className.equals(entry.stringValue("name").orElse(null))) {
                    return entry;
                }
            }
        }
        return null;
    }
}
