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

/**
 * The harness ArC's tests register, standing in for the one ArC's own test module carries.
 *
 * <p>The package is Quarkus's because the tests fetched into this module are: every one of them registers an
 * {@code ArcTestContainer} of this package as a JUnit extension, and they are fetched unmodified. ArC's own
 * version of this class builds the container as the test method begins — it indexes the classes the test named,
 * runs ArC's build over them and loads what that generated. Nothing of the sort can happen here, because the
 * classes were already built into bean definitions when the module compiled; what is left for a deployment to
 * decide is which of those definitions the container holds, and that is what the class here does.</p>
 */
package io.quarkus.arc.test;
