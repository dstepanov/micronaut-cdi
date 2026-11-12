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

import io.micronaut.context.condition.Condition;
import io.micronaut.context.condition.ConditionContext;
import io.micronaut.core.annotation.Internal;

/**
 * The condition of a producer that is not enabled because it is a reserve no priority of its own selected, or a
 * producer of a reserve class no priority selected (CDI 5.0, sections 5.1.2 and 5.1.3).
 *
 * <p>Unlike {@link NotABean}, which takes a class out of the beans and leaves what its producers make to them, this
 * takes the producer itself out: it never holds. A reserve is selected by a priority alone - no bootstrap selects
 * one otherwise - so there is nothing for it to read.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class UnselectedReserve implements Condition {

    @Override
    public boolean matches(ConditionContext context) {
        return false;
    }
}
