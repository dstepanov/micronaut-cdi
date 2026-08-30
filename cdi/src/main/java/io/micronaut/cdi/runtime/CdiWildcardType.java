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
package io.micronaut.cdi.runtime;

import io.micronaut.core.annotation.Internal;

import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.StringJoiner;

/**
 * A wildcard rebuilt from what the compiler recorded of one: its bounds, which is all the matching rules of
 * section 2.4.2.1 read of a wildcard.
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
final class CdiWildcardType implements WildcardType {

    private final Type[] upperBounds;
    private final Type[] lowerBounds;

    CdiWildcardType(Type[] upperBounds, Type[] lowerBounds) {
        this.upperBounds = upperBounds;
        this.lowerBounds = lowerBounds;
    }

    @Override
    public Type[] getUpperBounds() {
        return upperBounds.clone();
    }

    @Override
    public Type[] getLowerBounds() {
        return lowerBounds.clone();
    }

    @Override
    public String toString() {
        StringJoiner joiner = new StringJoiner(" & ");
        if (lowerBounds.length > 0) {
            for (Type bound : lowerBounds) {
                joiner.add(bound.getTypeName());
            }
            return "? super " + joiner;
        }
        for (Type bound : upperBounds) {
            joiner.add(bound.getTypeName());
        }
        return "? extends " + joiner;
    }
}
