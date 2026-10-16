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

import io.micronaut.cdi.test.index.hidden.HiddenPayload;
import io.micronaut.core.type.Argument;
import io.micronaut.core.type.TypeHierarchies;
import io.micronaut.core.type.TypeHierarchy;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The generic hierarchy of a class no definition describes is recorded as Micronaut's type hierarchy record of the
 * class, which Micronaut places where the classes it refers to can be referred to: a package-private supertype from
 * its own package. A class of the JDK named as a type argument is recorded too, in the package of the class naming
 * it.
 */
class TypeIndexRecordTest {

    @Test
    void aPackagePrivateSupertypeIsRecordedAsItsClass() {
        TypeHierarchy hierarchy = TypeHierarchies.find(HiddenPayload.class).orElse(null);
        assertTrue(hierarchy != null, "the payload is recorded");
        List<Argument<?>> closure = hierarchy.getTypeClosure(hierarchy.getDeclaration());
        Argument<?> base = closure.get(1);
        assertEquals("io.micronaut.cdi.test.index.hidden.HiddenBase", base.getType().getName(),
            "the package-private supertype is recorded as a class, from its own package");
        assertEquals(Integer.class, base.getTypeParameters()[0].getType());
    }

    @Test
    void aClassOfTheJdkNamedAsATypeArgumentIsRecorded() {
        TypeHierarchy integer = TypeHierarchies.find(Integer.class).orElse(null);
        assertTrue(integer != null, "the class named as the argument of a recorded supertype is recorded");
        assertTrue(integer.getSuperTypes().stream()
                .anyMatch(type -> type.getType() == Comparable.class
                    && type.getTypeParameters()[0].getType() == Integer.class),
            "the JDK class is recorded with its generic interfaces");
    }
}
