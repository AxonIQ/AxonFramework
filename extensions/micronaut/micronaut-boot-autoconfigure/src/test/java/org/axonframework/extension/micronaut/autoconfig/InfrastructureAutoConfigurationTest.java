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

package org.axonframework.extension.micronaut.autoconfig;

import org.axonframework.extension.micronaut.config.MessageHandlerLookup;
import org.axonframework.extension.micronaut.config.MicronautEventSourcedMethodProcessor;
import org.junit.jupiter.api.*;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
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
                .withPropertyValues("axon.axonserver.enabled:false", "axon.eventstorage.jpa.polling-interval:0");
    }

    @Test
    public void initializesComponents() {
        testApplicationContext.run(context -> {
                                       MicronautEventSourcedMethodProcessor micronautEventSourcedMethodProcessor = context.getBean(
                                               MicronautEventSourcedMethodProcessor.class);
                                       assertThat(micronautEventSourcedMethodProcessor).isNotNull();

                                       MessageHandlerLookup messageHandlerLookup = context.getBean(MessageHandlerLookup.class);
                                       assertThat(messageHandlerLookup).isNotNull();
                                   }
        );
    }


    @ContextConfiguration
    @EnableAutoConfiguration
    @EnableMBeanExport(registration = RegistrationPolicy.IGNORE_EXISTING)
    static class DefaultContext {

    }
}
