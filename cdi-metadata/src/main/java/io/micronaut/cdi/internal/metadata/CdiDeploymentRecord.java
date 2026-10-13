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
package io.micronaut.cdi.internal.metadata;

import io.micronaut.core.annotation.Internal;

/**
 * The type of the bean a compilation registers for what its build compatible extensions said of the deployment
 * as a whole rather than of a class - the annotations they made qualifiers of ({@link CdiExtensionQualifiers}) -
 * which is recorded on the definition of that bean. The container reads the record from the definition, and never
 * creates the bean.
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class CdiDeploymentRecord {
}
