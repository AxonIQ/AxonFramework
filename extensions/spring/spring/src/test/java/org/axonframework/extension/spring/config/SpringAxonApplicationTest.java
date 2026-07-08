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

import org.axonframework.common.TypeReference;
import org.axonframework.common.configuration.ApplicationConfigurerTestSuite;
import org.axonframework.common.configuration.AxonConfiguration;
import org.axonframework.common.configuration.ComponentNotFoundException;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.BeanNotOfRequiredTypeException;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Test suite implementation validating the {@link SpringAxonApplication}.
 * <p>
 * Overrides {@link #supportsOverriding()} to return {@code false}, since Spring does not allow bean overriding.
 * <p>
 * Overrides {@link #doesOwnLifecycleManagement()} to return {@code false}, since all lifecycle management is given to
 * Spring instead of done manually.
 *
 * @author Steven van Beelen
 */
class SpringAxonApplicationTest extends ApplicationConfigurerTestSuite<SpringAxonApplication> {

    private SpringComponentRegistry componentRegistry;
    private DefaultListableBeanFactory beanFactory;

    @Override
    public SpringAxonApplication createConfigurer() {
        beanFactory = new DefaultListableBeanFactory();
        SpringLifecycleRegistry lifecycleRegistry = new SpringLifecycleRegistry();
        lifecycleRegistry.setBeanFactory(beanFactory);
        componentRegistry = new SpringComponentRegistry(beanFactory, lifecycleRegistry);
        componentRegistry.postProcessBeanFactory(beanFactory);
        return new SpringAxonApplication(componentRegistry, lifecycleRegistry);
    }

    @Override
    protected void initialize(SpringAxonApplication testSubject) {
        componentRegistry.postProcessAfterInitialization(new Object(), "something");
    }

    @Override
    public boolean supportsOverriding() {
        return false;
    }

    @Override
    public boolean supportsComponentFactories() {
        return false;
    }

    @Override
    public boolean doesOwnLifecycleManagement() {
        return false;
    }

    @Test
    void getOptionalComponentShouldNeverThrowsExceptions() {
        AxonConfiguration config = testSubject.build();

        assertThatNoException().isThrownBy(
                () -> config.getOptionalComponent(TestComponent.class)
        );
    }

    @Test
    void getComponentThrowsComponentNotFoundExceptionWhenSpringBeanIsMissing() {
        AxonConfiguration config = testSubject.build();

        assertThatThrownBy(() -> config.getComponent(TestComponent.class))
                .isInstanceOf(ComponentNotFoundException.class)
                .hasCauseInstanceOf(NoSuchBeanDefinitionException.class);
    }

    @Test
    void getComponentByNameThrowsComponentNotFoundExceptionWhenSpringBeanIsMissing() {
        AxonConfiguration config = testSubject.build();

        assertThatThrownBy(() -> config.getComponent(TestComponent.class, "testComponent"))
                .isInstanceOf(ComponentNotFoundException.class)
                .hasCauseInstanceOf(NoSuchBeanDefinitionException.class);
    }

    @Test
    void getComponentByTypeReferenceAndNameThrowsComponentNotFoundExceptionWhenSpringBeanIsMissing() {
        AxonConfiguration config = testSubject.build();

        assertThatThrownBy(() -> config.getComponent(new TypeReference<TestComponent>() {
        }, "testComponent"))
                .isInstanceOf(ComponentNotFoundException.class)
                .hasMessageContaining("testComponent")
                .hasCauseInstanceOf(NoSuchBeanDefinitionException.class);
    }

    @Test
    void getComponentByNameThrowsComponentNotFoundExceptionWhenSpringBeanHasWrongType() {
        beanFactory.registerSingleton("testComponent", "notATestComponent");
        AxonConfiguration config = testSubject.build();

        assertThatThrownBy(() -> config.getComponent(TestComponent.class, "testComponent"))
                .isInstanceOf(ComponentNotFoundException.class)
                .hasMessageContaining("testComponent")
                .hasCauseInstanceOf(BeanNotOfRequiredTypeException.class);
    }
}
