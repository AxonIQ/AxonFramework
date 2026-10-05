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

package org.axonframework.extension.springboot.autoconfig;

import org.axonframework.eventsourcing.eventstore.EventStorageEngine;
import org.axonframework.eventsourcing.eventstore.inmemory.InMemoryEventStorageEngine;
import org.axonframework.extension.spring.config.MessageHandlerLookup;
import org.axonframework.extension.spring.config.SpringEventSourcedEntityLookup;
import org.axonframework.extension.springboot.polymorphicentity.AbstractCourse;
import org.axonframework.modelling.StateManager;
import org.junit.jupiter.api.*;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableMBeanExport;
import org.springframework.jmx.support.RegistrationPolicy;
import org.springframework.test.context.ContextConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test class validating the behavior of the {@link InfrastructureAutoConfiguration}.
 *
 * @author Simon Zambrovski
 */
class InfrastructureAutoConfigurationTest {

    private ApplicationContextRunner testApplicationContext;

    @BeforeEach
    void setUp() {
        testApplicationContext = new ApplicationContextRunner()
                .withUserConfiguration(DefaultContext.class)
                .withPropertyValues("axon.eventstorage.jpa.polling-interval:0");
    }

    @Test
    public void initializesComponents() {
        testApplicationContext.run(context -> {
                                       SpringEventSourcedEntityLookup springEventSourcedEntityLookup = context.getBean(
                                               SpringEventSourcedEntityLookup.class);
                                       assertThat(springEventSourcedEntityLookup).isNotNull();

                                       MessageHandlerLookup messageHandlerLookup = context.getBean(MessageHandlerLookup.class);
                                       assertThat(messageHandlerLookup).isNotNull();
                                   }
        );
    }

    @Test
    void registersRepositoryForAbstractPolymorphicEntityWithoutAnnotatingSubtypes() {
        testApplicationContext.withUserConfiguration(PolymorphicEntityContext.class).run(context -> {
            org.axonframework.common.configuration.Configuration configuration =
                    context.getBean(org.axonframework.common.configuration.Configuration.class);
            StateManager stateManager = configuration.getComponent(StateManager.class);

            assertThat(stateManager.repository(AbstractCourse.class, String.class)).isNotNull();
        });
    }


    @ContextConfiguration
    @EnableAutoConfiguration
    @EnableMBeanExport(registration = RegistrationPolicy.IGNORE_EXISTING)
    static class DefaultContext {

    }

    /**
     * Scoped to {@link AbstractCourse}'s own package (rather than this test's package) so
     * {@code AutoConfigurationPackages}-based scanning, and thus the {@link AbstractCourse} discovery under test,
     * stays isolated to this test and doesn't affect other tests sharing this class's package.
     */
    @Configuration
    @AutoConfigurationPackage(basePackageClasses = AbstractCourse.class)
    static class PolymorphicEntityContext {

        @Bean
        public EventStorageEngine eventStorageEngine() {
            return new InMemoryEventStorageEngine();
        }
    }
}
