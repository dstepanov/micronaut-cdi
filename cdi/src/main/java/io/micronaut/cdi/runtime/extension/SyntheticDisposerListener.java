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
package io.micronaut.cdi.runtime.extension;

import io.micronaut.context.BeanContext;
import io.micronaut.context.event.BeanPreDestroyEvent;
import io.micronaut.context.event.BeanPreDestroyEventListener;
import io.micronaut.core.annotation.Internal;
import jakarta.inject.Singleton;

/**
 * Runs the disposal function of a synthetic bean as its instance is destroyed (section 2.10.5): the extension
 * said what disposes of the bean, and this is the moment it meant.
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Singleton
@Internal
public final class SyntheticDisposerListener implements BeanPreDestroyEventListener<Object> {

    private final BeanContext beanContext;

    public SyntheticDisposerListener(BeanContext beanContext) {
        this.beanContext = beanContext;
    }

    @SuppressWarnings("unchecked")
    @Override
    public Object onPreDestroy(BeanPreDestroyEvent<Object> event) {
        beanContext.findBean(SynthesisRunner.class).ifPresent(runner -> {
            SyntheticBean<?> described = runner.describedBeanOf(event.getBeanDefinition());
            if (described != null) {
                runner.dispose((SyntheticBean<Object>) described, event.getBean());
            }
        });
        return event.getBean();
    }
}
