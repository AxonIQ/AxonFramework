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

import org.axonframework.messaging.core.MessageHandlerInterceptorChain;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.axonframework.messaging.eventhandling.gateway.EventGateway;
import org.axonframework.messaging.eventhandling.interception.annotation.EventHandlerInterceptor;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.TokenStore;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.inmemory.InMemoryTokenStore;
import org.junit.jupiter.api.*;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableMBeanExport;
import org.springframework.jmx.support.RegistrationPolicy;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test class verifying that {@link EventHandlerInterceptor @EventHandlerInterceptor} methods on an auto-detected
 * Spring component are invoked correctly alongside the component's {@link EventHandler @EventHandler} methods.
 *
 * @author Allard Buijze
 */
class AnnotatedEventHandlerInterceptorTest {

    private ApplicationContextRunner testContext;

    @BeforeEach
    void setUp() {
        testContext = new ApplicationContextRunner()
                .withUserConfiguration(DefaultContext.class)
                .withPropertyValues("axon.eventstorage.jpa.polling-interval=0");
    }

    @Nested
    class GivenBeforeInterceptorOnComponent {

        @Test
        void interceptorIsInvokedBeforeEventHandler() {
            testContext.withUserConfiguration(BeforeInterceptorContext.class).run(context -> {
                context.getBean(EventGateway.class).publish(null, "test-event");

                CountDownLatch handlerInvoked = context.getBean("handlerInvoked", CountDownLatch.class);
                assertThat(handlerInvoked.await(2, TimeUnit.SECONDS)).isTrue();

                //noinspection unchecked
                List<String> invocations = context.getBean("invocations", List.class);
                assertThat(invocations).containsExactly("interceptor", "handler");
            });
        }
    }

    @Nested
    class GivenSurroundInterceptorOnComponent {

        @Test
        void surroundInterceptorInvokesEventHandlerViaChain() {
            testContext.withUserConfiguration(SurroundInterceptorContext.class).run(context -> {
                context.getBean(EventGateway.class).publish(null, "test-event");

                CountDownLatch handlerInvoked = context.getBean("handlerInvoked", CountDownLatch.class);
                assertThat(handlerInvoked.await(2, TimeUnit.SECONDS)).isTrue();

                //noinspection unchecked
                List<String> invocations = context.getBean("invocations", List.class);
                assertThat(invocations).containsExactly("interceptor", "handler");
            });
        }

        @Test
        void surroundInterceptorCanShortCircuitHandling() {
            testContext.withUserConfiguration(ShortCircuitInterceptorContext.class).run(context -> {
                context.getBean(EventGateway.class).publish(null, "test-event");

                CountDownLatch interceptorInvoked = context.getBean("interceptorInvoked", CountDownLatch.class);
                assertThat(interceptorInvoked.await(2, TimeUnit.SECONDS)).isTrue();

                //noinspection unchecked
                List<String> invocations = context.getBean("invocations", List.class);
                assertThat(invocations).containsExactly("interceptor");
            });
        }
    }

    @Configuration
    @EnableAutoConfiguration
    @EnableMBeanExport(registration = RegistrationPolicy.IGNORE_EXISTING)
    static class DefaultContext {

        @Bean
        public TokenStore tokenStore() {
            return new InMemoryTokenStore();
        }
    }

    static class BeforeInterceptorContext {

        @Bean
        public List<String> invocations() {
            return new CopyOnWriteArrayList<>();
        }

        @Bean
        public CountDownLatch handlerInvoked() {
            return new CountDownLatch(1);
        }

        @Bean
        public BeforeInterceptorHandlerComponent handlerComponent(List<String> invocations,
                                                                   CountDownLatch handlerInvoked) {
            return new BeforeInterceptorHandlerComponent(invocations, handlerInvoked);
        }

        @SuppressWarnings("unused")
        static class BeforeInterceptorHandlerComponent {

            private final List<String> invocations;
            private final CountDownLatch handlerInvoked;

            BeforeInterceptorHandlerComponent(List<String> invocations, CountDownLatch handlerInvoked) {
                this.invocations = invocations;
                this.handlerInvoked = handlerInvoked;
            }

            @EventHandlerInterceptor
            void intercept(EventMessage event) {
                invocations.add("interceptor");
            }

            @EventHandler
            void on(String event) {
                invocations.add("handler");
                handlerInvoked.countDown();
            }
        }
    }

    static class SurroundInterceptorContext {

        @Bean
        public List<String> invocations() {
            return new CopyOnWriteArrayList<>();
        }

        @Bean
        public CountDownLatch handlerInvoked() {
            return new CountDownLatch(1);
        }

        @Bean
        public SurroundInterceptorHandlerComponent handlerComponent(List<String> invocations,
                                                                     CountDownLatch handlerInvoked) {
            return new SurroundInterceptorHandlerComponent(invocations, handlerInvoked);
        }

        @SuppressWarnings("unused")
        static class SurroundInterceptorHandlerComponent {

            private final List<String> invocations;
            private final CountDownLatch handlerInvoked;

            SurroundInterceptorHandlerComponent(List<String> invocations, CountDownLatch handlerInvoked) {
                this.invocations = invocations;
                this.handlerInvoked = handlerInvoked;
            }

            @EventHandlerInterceptor
            MessageStream<?> intercept(EventMessage event,
                                       MessageHandlerInterceptorChain<EventMessage> chain,
                                       ProcessingContext ctx) {
                invocations.add("interceptor");
                return chain.proceed(event, ctx);
            }

            @EventHandler
            void on(String event) {
                invocations.add("handler");
                handlerInvoked.countDown();
            }
        }
    }

    static class ShortCircuitInterceptorContext {

        @Bean
        public List<String> invocations() {
            return new CopyOnWriteArrayList<>();
        }

        @Bean
        public CountDownLatch interceptorInvoked() {
            return new CountDownLatch(1);
        }

        @Bean
        public ShortCircuitInterceptorHandlerComponent handlerComponent(List<String> invocations,
                                                                         CountDownLatch interceptorInvoked) {
            return new ShortCircuitInterceptorHandlerComponent(invocations, interceptorInvoked);
        }

        @SuppressWarnings("unused")
        static class ShortCircuitInterceptorHandlerComponent {

            private final List<String> invocations;
            private final CountDownLatch interceptorInvoked;

            ShortCircuitInterceptorHandlerComponent(List<String> invocations, CountDownLatch interceptorInvoked) {
                this.invocations = invocations;
                this.interceptorInvoked = interceptorInvoked;
            }

            @EventHandlerInterceptor
            MessageStream<?> intercept(EventMessage event,
                                       MessageHandlerInterceptorChain<EventMessage> chain,
                                       ProcessingContext ctx) {
                invocations.add("interceptor");
                interceptorInvoked.countDown();
                return MessageStream.empty();
            }

            @EventHandler
            void on(String event) {
                invocations.add("handler");
            }
        }
    }
}
