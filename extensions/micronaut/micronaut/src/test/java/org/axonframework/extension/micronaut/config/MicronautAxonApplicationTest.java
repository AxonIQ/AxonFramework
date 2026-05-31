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

package org.axonframework.extension.micronaut.config;

import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Provider;
import org.axonframework.common.configuration.ApplicationConfigurerTestSuite;

import javax.annotation.Nonnull;

/**
 * Test suite implementation validating the {@link MicronautAxonApplication}.
 * <p>
 * Overrides {@link #supportsOverriding()} to return {@code false}, since Spring does not allow bean overriding.
 * <p>
 * Overrides {@link #doesOwnLifecycleManagement()} to return {@code false}, since all lifecycle management is given to
 * Spring instead of done manually.
 *
 * @author Steven van Beelen
 */
@MicronautTest
class MicronautAxonApplicationTest extends ApplicationConfigurerTestSuite<MicronautAxonApplication> {

    private MicronautComponentRegistry componentRegistry;
    private MicronautLifecycleRegistry lifecycleRegistry;
    private Provider<MicronautAxonApplication> micronautAxonApplicationProvider;

    MicronautAxonApplicationTest(@Nonnull MicronautComponentRegistry componentRegistry,
                                 MicronautLifecycleRegistry lifecycleRegistry,
                                 @Nonnull Provider<MicronautAxonApplication> micronautAxonApplicationProvider) {
        this.componentRegistry = componentRegistry;
        this.lifecycleRegistry = lifecycleRegistry;
        this.micronautAxonApplicationProvider = micronautAxonApplicationProvider;
    }

    @Override
    public MicronautAxonApplication createConfigurer() {
//        ConfigurableListableBeanFactory beanFactory = new DefaultListableBeanFactory();
//        lifecycleRegistry.setBeanFactory(beanFactory);
//        componentRegistry = new MicronautComponentRegistry(beanFactory, lifecycleRegistry);
//        componentRegistry.postProcessBeanFactory(beanFactory);
        return micronautAxonApplicationProvider.get();
//        return new MicronautAxonApplication(componentRegistry, lifecycleRegistry);
    }

    @Override
    protected void initialize(MicronautAxonApplication testSubject) {
//        componentRegistry.postProcessAfterInitialization(new Object(), "something");
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
}