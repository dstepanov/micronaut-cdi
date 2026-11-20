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
package io.micronaut.cdi.test.bce;

import io.micronaut.cdi.internal.runtime.CdiBeanContainer;
import io.micronaut.context.ApplicationContext;
import jakarta.enterprise.inject.build.compatible.spi.BeanInfo;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.Registration;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A dollar a top level class was named with is part of its name: {@code ClassInfo.simpleName()} is the whole of
 * it, and so is the default name of the bean (section 2.6.3). Only a nested class's binary name has a dollar
 * standing for the nesting.
 */
class DollarClassNameTest {

    private static final String SOURCE = """
        package priced;

        @jakarta.enterprise.context.Dependent
        @jakarta.inject.Named
        public class Price$USD {

            @jakarta.enterprise.context.Dependent
            public static class Cents {
            }
        }
        """;

    @Test
    void aDollarInTheNameOfATopLevelClassIsPartOfItsSimpleNameAndItsBeanName() {
        Recording.SIMPLE_NAMES.clear();
        try (ApplicationContext context = ExtensionDeployment.start(Recording.class, "priced.Price$USD", SOURCE)) {
            assertEquals(List.of("Cents", "Price$USD"), Recording.SIMPLE_NAMES.stream().sorted().toList());
            assertEquals(1, context.getBean(CdiBeanContainer.class).getBeans("price$USD").size());
        }
    }

    /**
     * Records the simple names of the classes of the beans in the package.
     */
    public static final class Recording implements BuildCompatibleExtension {

        static final List<String> SIMPLE_NAMES = new CopyOnWriteArrayList<>();

        @Registration(types = Object.class)
        public void register(BeanInfo bean) {
            if (bean.isClassBean() && bean.declaringClass().packageInfo().name().equals("priced")) {
                SIMPLE_NAMES.add(bean.declaringClass().simpleName());
            }
        }
    }
}
