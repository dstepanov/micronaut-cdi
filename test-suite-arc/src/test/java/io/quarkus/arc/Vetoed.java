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
package io.quarkus.arc;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Keeps a class, or every class of a package, from being a bean.
 *
 * <p>This is ArC's own: CDI Lite dropped the {@code @Vetoed} of earlier versions along with the extensions that
 * could veto a class, and ArC kept it because a test needs to say that a class on the classpath is not to be
 * discovered. It is honoured here where the deployment is decided rather than where the class is compiled — the
 * class compiles into a bean definition as any other, and the container the extension narrows leaves the
 * definition out — which is the same answer from the point of view of a test.</p>
 */
@Target({ElementType.TYPE, ElementType.PACKAGE})
@Retention(RetentionPolicy.RUNTIME)
public @interface Vetoed {
}
