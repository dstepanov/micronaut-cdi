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
package org.jboss.weld.resolution;

import java.lang.reflect.Type;

/**
 * What Weld's assignability unit tests ask of Weld, answered here by this implementation: the interface of
 * Weld's own rules, as far as the tests reach it.
 */
public interface AssignabilityRules {

    /**
     * @param requiredType The type required: of an injection point, or observed by an observer
     * @param beanType     The type offered: of a bean, or of an event
     * @return Whether the offered type is assignable to the required one by the rules of section 2.4.2
     */
    boolean matches(Type requiredType, Type beanType);
}
