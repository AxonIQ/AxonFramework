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

import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.axonframework.messaging.eventhandling.gateway.EventGateway;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.TokenStore;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.inmemory.InMemoryTokenStore;
import org.junit.jupiter.api.*;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Test class validating that Spring {@link Component Components} with an {@link EventHandler} annotated method are
 * invoked in the order defined through {@link Order @Order}, when an event is published.
 * <p>
 * The handlers are intentionally declared so that neither their bean names nor their declaration order match their
 * {@code @Order} value, to ensure that the outcome is not accidentally correct.
 *
 * @author Steven van Beelen
 */
class EventHandlingComponentOrderTest {

    private ApplicationContextRunner testContext;

    @BeforeEach
    void setUp() {
        testContext = new ApplicationContextRunner()
                .withUserConfiguration(TestContext.class)
                .withPropertyValues("axon.eventstorage.jpa.polling-interval=0");
    }

    @SuppressWarnings("unchecked")
    @Test
    void eventHandlersAreInvokedInOrderOfTheOrderAnnotation() {
        testContext.run(context -> {
            // given
            List<String> invocations = context.getBean("invocations", List.class);

            // when
            context.getBean(EventGateway.class).publish(null, "some-event");

            // then
            await().pollDelay(Duration.ofMillis(50))
                   .untilAsserted(() -> assertThat(invocations).containsExactly(
                           "order-1", "order-2", "order-3", "order-4", "order-5", "order-6", "order-7", "order-8"
                   ));
        });
    }

    @Configuration
    @EnableAutoConfiguration
    static class TestContext {

        @Bean
        public List<String> invocations() {
            return new CopyOnWriteArrayList<>();
        }

        @Bean
        public TokenStore tokenStore() {
            return new InMemoryTokenStore();
        }

        @Component
        @Order(5)
        static class AlphaHandler extends RecordingHandler {

            AlphaHandler(List<String> invocations) {
                super("order-5", invocations);
            }
        }

        @Component
        @Order(8)
        static class BravoHandler extends RecordingHandler {

            BravoHandler(List<String> invocations) {
                super("order-8", invocations);
            }
        }

        @Component
        @Order(2)
        static class CharlieHandler extends RecordingHandler {

            CharlieHandler(List<String> invocations) {
                super("order-2", invocations);
            }
        }

        @Component
        @Order(7)
        static class DeltaHandler extends RecordingHandler {

            DeltaHandler(List<String> invocations) {
                super("order-7", invocations);
            }
        }

        @Component
        @Order(1)
        static class EchoHandler extends RecordingHandler {

            EchoHandler(List<String> invocations) {
                super("order-1", invocations);
            }
        }

        @Component
        @Order(4)
        static class FoxtrotHandler extends RecordingHandler {

            FoxtrotHandler(List<String> invocations) {
                super("order-4", invocations);
            }
        }

        @Component
        @Order(6)
        static class GolfHandler extends RecordingHandler {

            GolfHandler(List<String> invocations) {
                super("order-6", invocations);
            }
        }

        @Component
        @Order(3)
        static class HotelHandler extends RecordingHandler {

            HotelHandler(List<String> invocations) {
                super("order-3", invocations);
            }
        }

        abstract static class RecordingHandler {

            private final String name;
            private final List<String> invocations;

            RecordingHandler(String name, List<String> invocations) {
                this.name = name;
                this.invocations = invocations;
            }

            @SuppressWarnings("unused")
            @EventHandler
            public void on(String event) {
                invocations.add(name);
            }
        }
    }
}
