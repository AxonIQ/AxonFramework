/*
 * Copyright (c) 2010-2026. Axon Framework
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.axonframework.extension.spring.config;

import org.axonframework.common.configuration.ComponentRegistry;
import org.axonframework.common.configuration.ConfigurationEnhancer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;

import static org.assertj.core.api.Assertions.assertThat;

class SpringComponentRegistryInitializationTest {

    @Test
    void shouldDeferInitializationForInfrastructureBeans() {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        SpringLifecycleRegistry lifecycleRegistry = new SpringLifecycleRegistry();
        lifecycleRegistry.setBeanFactory(beanFactory);

        RootBeanDefinition infrastructureBean = new RootBeanDefinition(Object.class);
        infrastructureBean.setRole(RootBeanDefinition.ROLE_INFRASTRUCTURE);
        beanFactory.registerBeanDefinition("infrastructureBean", infrastructureBean);
        beanFactory.registerBeanDefinition("applicationBean", new RootBeanDefinition(Object.class));
        beanFactory.registerBeanDefinition(
                "testEnhancer",
                BeanDefinitionBuilder.rootBeanDefinition(TestEnhancer.class).getBeanDefinition()
        );

        SpringComponentRegistry testSubject = new SpringComponentRegistry(beanFactory, lifecycleRegistry);
        testSubject.postProcessBeanFactory(beanFactory);

        testSubject.postProcessAfterInitialization(new Object(), "infrastructureBean");
        assertThat(beanFactory.containsSingleton("testEnhancer")).isFalse();

        testSubject.postProcessAfterInitialization(new Object(), "applicationBean");
        assertThat(beanFactory.containsSingleton("testEnhancer")).isTrue();
    }

    @SuppressWarnings("unused")
    private static class TestEnhancer implements ConfigurationEnhancer {

        @Override
        public void enhance(ComponentRegistry registry) {
            // No-op
        }
    }
}
