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

import org.axonframework.eventsourcing.handler.tracing.annotation.TracingEventTagsHandlerEnhancerDefinition;
import org.axonframework.messaging.core.annotation.HandlerDefinition;
import org.axonframework.messaging.core.annotation.HandlerEnhancerDefinition;
import org.axonframework.messaging.core.annotation.MultiHandlerDefinition;
import org.axonframework.messaging.core.annotation.MultiHandlerEnhancerDefinition;
import org.axonframework.messaging.tracing.LoggingSpanFactory;
import org.axonframework.messaging.tracing.SpanFactory;
import org.axonframework.messaging.tracing.annotation.TracingHandlerEnhancerDefinition;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that the tracing {@link HandlerEnhancerDefinition}s participate in the combined {@code handlerDefinition}
 * bean only when a {@link SpanFactory} bean is present: with one, both the per-method and the event-tags tracing
 * enhancers are part of the handler chain; without one, the chain is completely free of tracing enhancers and no
 * handler is ever wrapped.
 */
class HandlerDefinitionTracingDecorationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withPropertyValues("axon.eventstorage.jpa.polling-interval=0");

    private static List<HandlerEnhancerDefinition> enhancersOf(HandlerDefinition handlerDefinition) {
        assertThat(handlerDefinition).isInstanceOf(MultiHandlerDefinition.class);
        HandlerEnhancerDefinition enhancers =
                ((MultiHandlerDefinition) handlerDefinition).getHandlerEnhancerDefinition();
        assertThat(enhancers).isInstanceOf(MultiHandlerEnhancerDefinition.class);
        return ((MultiHandlerEnhancerDefinition) enhancers).getDelegates();
    }

    @Test
    void handlerDefinitionContainsTheTracingEnhancersWhenASpanFactoryBeanExists() {
        // given / when
        contextRunner.withUserConfiguration(TracingContext.class).run(context -> {
            // then both conditional tracing enhancers are part of the combined handler chain
            List<HandlerEnhancerDefinition> enhancers = enhancersOf(context.getBean(HandlerDefinition.class));
            assertThat(enhancers)
                    .anyMatch(enhancer -> enhancer instanceof TracingHandlerEnhancerDefinition)
                    .anyMatch(enhancer -> enhancer instanceof TracingEventTagsHandlerEnhancerDefinition);
        });
    }

    @Test
    void handlerDefinitionContainsNoTracingEnhancersWithoutASpanFactoryBean() {
        // given / when
        contextRunner.withUserConfiguration(PlainContext.class).run(context -> {
            // then the handler chain is completely free of tracing enhancers
            List<HandlerEnhancerDefinition> enhancers = enhancersOf(context.getBean(HandlerDefinition.class));
            assertThat(enhancers)
                    .noneMatch(enhancer -> enhancer instanceof TracingHandlerEnhancerDefinition)
                    .noneMatch(enhancer -> enhancer instanceof TracingEventTagsHandlerEnhancerDefinition);
        });
    }

    @Configuration
    @EnableAutoConfiguration
    static class PlainContext {

    }

    @Configuration
    @EnableAutoConfiguration
    static class TracingContext {

        @Bean
        SpanFactory spanFactory() {
            return LoggingSpanFactory.INSTANCE;
        }
    }
}
