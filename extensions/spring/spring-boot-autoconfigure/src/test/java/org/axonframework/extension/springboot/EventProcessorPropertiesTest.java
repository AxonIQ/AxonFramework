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

package org.axonframework.extension.springboot;

import org.axonframework.extension.spring.config.EventProcessorSettings;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.TokenStore;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.inmemory.InMemoryTokenStore;
import org.junit.jupiter.api.*;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.stereotype.Component;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test class validating how {@link EventProcessorProperties.ProcessorSettings} bound from application properties
 * describe the {@link TokenStore} of a pooled processor.
 *
 * @author Stefan Dragisic
 */
class EventProcessorPropertiesTest {

    private static final String PROCESSOR_NAME = "test-processor";
    private static final String PROCESSOR_PREFIX = "axon.eventhandling.processors[" + PROCESSOR_NAME + "]";

    @Nested
    class UnsetTokenStoreProperty {

        @Test
        void bindingWithoutATokenStorePropertyLeavesTheTokenStoreNameUnset() {
            // given - an application that configures a processor without naming a token store
            Environment environment = environmentWith(PROCESSOR_PREFIX + ".mode", "pooled");

            // when
            EventProcessorSettings settings = EventProcessorProperties.getProcessors(environment).get(PROCESSOR_NAME);

            // then - an unset name is what makes the token store optional while customizing the processor
            assertThat(settings).isInstanceOf(EventProcessorSettings.PooledEventProcessorSettings.class);
            assertThat(((EventProcessorSettings.PooledEventProcessorSettings) settings).tokenStore()).isNull();
        }

        @Test
        void startsAnApplicationWhoseTokenStoreBeanIsNotNamedTokenStore() {
            // given - the only TokenStore bean is named "customTokenStore", and no token-store property is set
            // when / then - the unset name resolves the TokenStore by type instead of demanding a named bean
            new ApplicationContextRunner()
                    .withUserConfiguration(TestContext.class)
                    .run(context -> assertThat(context).hasNotFailed());
        }
    }

    @Nested
    class ExplicitlySetTokenStoreProperty {

        @Test
        void bindingATokenStorePropertyKeepsTheConfiguredName() {
            // given
            Environment environment = environmentWith(PROCESSOR_PREFIX + ".mode", "pooled",
                                                      PROCESSOR_PREFIX + ".token-store", "my-token-store");

            // when
            EventProcessorSettings settings = EventProcessorProperties.getProcessors(environment).get(PROCESSOR_NAME);

            // then
            assertThat(((EventProcessorSettings.PooledEventProcessorSettings) settings).tokenStore())
                    .isEqualTo("my-token-store");
        }

        @Test
        void failsToStartAnApplicationNamingAnUnknownTokenStore() {
            // given / when / then - an explicitly named token store stays mandatory
            new ApplicationContextRunner()
                    .withUserConfiguration(TestContext.class)
                    .withPropertyValues("axon.eventhandling.processors[..default].token-store=unknown-token-store")
                    .run(context -> assertThat(context).hasFailed());
        }
    }

    private static Environment environmentWith(String... keysAndValues) {
        MockEnvironment environment = new MockEnvironment();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            environment.setProperty(keysAndValues[i], keysAndValues[i + 1]);
        }
        return environment;
    }

    @Configuration
    @EnableAutoConfiguration
    static class TestContext {

        @Bean
        public TokenStore customTokenStore() {
            return new InMemoryTokenStore();
        }

        @SuppressWarnings("unused")
        @Component
        public static class EventHandlingComponent {

            @SuppressWarnings("unused")
            @EventHandler
            public void on(String event) {
                // a handler is required for a pooled processor to be constructed at all
            }
        }
    }
}
