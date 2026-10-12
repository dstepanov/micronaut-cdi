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
package io.micronaut.cdi.spi;

import java.util.List;

/**
 * Where an injection point injects, as the compiled metadata of its bean names it: the class, the member and,
 * for a parameter, the erased types of every parameter of its constructor or method and its position among
 * them. That identifies the member among overloads of one name, and the parameter among parameters of one
 * type, without reading anything back.
 *
 * <p>The types are the ones the definition of the bean compiled: a member the bean's class inherits from a
 * generic class has its type variables bound to the arguments the bean's class gives them, so a type may be a
 * subtype of the erased type the member declares.</p>
 *
 * @param declaringClass The class that declares the injection point, or a subclass of it
 * @param memberName     The name of the member, {@value #CONSTRUCTOR} for a constructor
 * @param field          Whether the member is a field
 * @param parameterTypes The erased types of the parameters of the constructor or method, empty for a field
 * @param index          The position of the injected parameter, {@code -1} for a field
 * @author Denis Stepanov
 * @since 1.0
 */
public record InjectionSite(Class<?> declaringClass,
                            String memberName,
                            boolean field,
                            List<Class<?>> parameterTypes,
                            int index) {

    /**
     * The name of a constructor, as the class file and Micronaut name it.
     */
    public static final String CONSTRUCTOR = "<init>";

    /**
     * @param declaringClass The class that declares the injection point, or a subclass of it
     * @param memberName     The name of the member, {@value #CONSTRUCTOR} for a constructor
     * @param field          Whether the member is a field
     * @param parameterTypes The erased types of the parameters of the constructor or method, empty for a field
     * @param index          The position of the injected parameter, {@code -1} for a field
     */
    public InjectionSite {
        parameterTypes = List.copyOf(parameterTypes);
    }

    /**
     * The site of an injected field.
     *
     * @param declaringClass The class that declares the field, or a subclass of it
     * @param name           The name of the field
     * @return The site
     */
    public static InjectionSite ofField(Class<?> declaringClass, String name) {
        return new InjectionSite(declaringClass, name, true, List.of(), -1);
    }

    /**
     * The site of an injected parameter of a constructor or a method.
     *
     * @param declaringClass The class that declares the constructor or method, or a subclass of it
     * @param memberName     The name of the method, {@value #CONSTRUCTOR} for a constructor
     * @param parameterTypes The erased types of every parameter of it
     * @param index          The position of the injected parameter
     * @return The site
     */
    public static InjectionSite ofParameter(Class<?> declaringClass, String memberName, List<Class<?>> parameterTypes,
                                            int index) {
        return new InjectionSite(declaringClass, memberName, false, parameterTypes, index);
    }

    /**
     * Whether the site is a parameter of a constructor.
     *
     * @return Whether it is
     */
    public boolean isConstructor() {
        return !field && CONSTRUCTOR.equals(memberName);
    }
}
