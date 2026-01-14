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

package org.axonframework.extension.micronaut;

import jakarta.annotation.Nonnull;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.axonframework.messaging.core.annotation.HandlerEnhancerDefinition;
import org.axonframework.messaging.core.annotation.MessageHandlingMember;
import org.junit.jupiter.api.*;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.EnableMBeanExport;
import org.springframework.jmx.support.RegistrationPolicy;
import org.springframework.test.context.ContextConfiguration;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class verifying a {@link HandlerEnhancerDefinition} configurations. For example, that a
 * {@code HandlerEnhancerDefinition} bean will only wrap if there are message handling functions present.
 *
 * @author Steven van Beelen
 */
@Disabled("TODO #3496")
class HandlerEnhancerDefinitionConfigurationTest {

    private static final AtomicBoolean VERIFY_ENHANCER = new AtomicBoolean(false);

    @BeforeEach
    void setUp() {
        VERIFY_ENHANCER.compareAndSet(true, false);
    }

    @Test
    void handlerEnhancerDefinitionWrapsEventHandler() {
        new ApplicationContextRunner()
                .withUserConfiguration(ContextWithHandlers.class)
                .withPropertyValues("axon.axonserver.enabled=false", "axon.eventstorage.jpa.polling-interval=0")
                .run(context -> {
                    assertThat(context).hasSingleBean(CustomHandlerEnhancerDefinition.class);
                    assertThat(context).hasSingleBean(MyEventHandlingComponent.class);

                    assertTrue(VERIFY_ENHANCER.get());
                });
    }

    @Test
    void handlerEnhancerDefinitionDoesNotWrapInAbsenceOfMessageHandlers() {
        new ApplicationContextRunner()
                .withUserConfiguration(ContextWithoutHandlers.class)
                .withPropertyValues("axon.axonserver.enabled=false", "axon.eventstorage.jpa.polling-interval=0")
                .run(context -> {
                    assertThat(context).hasSingleBean(CustomHandlerEnhancerDefinition.class);

                    assertFalse(VERIFY_ENHANCER.get());
                });
    }

    @ContextConfiguration
    @EnableAutoConfiguration
    @EnableMBeanExport(registration = RegistrationPolicy.IGNORE_EXISTING)
    private static class ContextWithHandlers {

        @Bean
        public HandlerEnhancerDefinition customHandlerEnhancerDefinition() {
            return new CustomHandlerEnhancerDefinition();
        }

        @Bean
        public MyEventHandlingComponent myEventHandlingComponent() {
            return new MyEventHandlingComponent();
        }
    }

    @ContextConfiguration
    @EnableAutoConfiguration
    @EnableMBeanExport(registration = RegistrationPolicy.IGNORE_EXISTING)
    private static class ContextWithoutHandlers {

        @Bean
        public HandlerEnhancerDefinition customHandlerEnhancerDefinition() {
            return new CustomHandlerEnhancerDefinition();
        }
    }

    private static class CustomHandlerEnhancerDefinition implements HandlerEnhancerDefinition {

        @Override
        public @Nonnull
        <T> MessageHandlingMember<T> wrapHandler(@Nonnull MessageHandlingMember<T> original) {
            VERIFY_ENHANCER.set(true);
            return original;
        }
    }

    private static class MyEventHandlingComponent {

        @EventHandler
        public void on(Object someEvent) {

        }
    }
}
