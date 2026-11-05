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

import org.axonframework.common.configuration.ComponentNotFoundException;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.MessageTypeResolver;
import org.axonframework.messaging.core.annotation.HandlerDefinition;
import org.axonframework.messaging.core.annotation.ParameterResolverFactory;
import org.axonframework.messaging.core.annotation.InjectComponent;
import org.axonframework.messaging.core.unitofwork.StubProcessingContext;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.GenericEventMessage;
import org.axonframework.messaging.eventhandling.annotation.AnnotatedEventHandlingComponent;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.axonframework.messaging.eventhandling.conversion.EventConverter;
import org.axonframework.messaging.eventhandling.gateway.EventGateway;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.TokenStore;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.inmemory.InMemoryTokenStore;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableMBeanExport;
import org.springframework.context.annotation.Primary;
import org.springframework.jmx.support.RegistrationPolicy;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test class validating {@link InjectComponent} annotated parameters of event handlers in a Spring Boot application,
 * where several beans of the same type exist.
 *
 * @author Allard Buijze
 */
class InjectComponentSpringTest {

    private ApplicationContextRunner testContext;

    @BeforeEach
    void setUp() {
        testContext = new ApplicationContextRunner()
                .withUserConfiguration(DefaultContext.class)
                .withPropertyValues("axon.axonserver.enabled=false");
    }

    @Nested
    class InPooledStreamingEventProcessor {

        @Test
        void injectsBeansByBeanNameAndByQualifier() {
            testContext.withUserConfiguration(GreetingHandlerContext.class).run(context -> {
                // given
                GreetingHandler handler = context.getBean(GreetingHandler.class);

                // when
                context.getBean(EventGateway.class).publish(null, "test-event");

                // then
                assertThat(handler.handled.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(handler.unannotated).isSameAs(context.getBean("primaryGreeter"));
                assertThat(handler.byBeanName).isSameAs(context.getBean("specialGreeter"));
                assertThat(handler.byQualifier).isSameAs(context.getBean("specialGreeter"));
            });
        }

        @Test
        void injectComponentTakesPrecedenceOverSpringQualifier() {
            testContext.withUserConfiguration(GreetingHandlerContext.class).run(context -> {
                // given
                GreetingHandler handler = context.getBean(GreetingHandler.class);

                // when
                context.getBean(EventGateway.class).publish(null, "test-event");

                // then
                assertThat(handler.handled.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(handler.conflictingQualifier).isSameAs(context.getBean("primaryGreeter"));
            });
        }

        @Test
        void failsOnStartupWhenNamedBeanIsMissing() {
            testContext.withUserConfiguration(MissingGreeterHandlerContext.class).run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                        .rootCause()
                        .isInstanceOf(ComponentNotFoundException.class)
                        .hasMessageContaining(Greeter.class.getName())
                        .hasMessageContaining("missingGreeter");
            });
        }
    }

    @Nested
    class WithApplicationParameterResolverFactory {

        @Test
        void injectComponentTakesPrecedenceOverSpringBeanParameterResolver() {
            testContext.withUserConfiguration(GreeterContext.class).run(context -> {
                // given
                ConflictingQualifierHandler handler = new ConflictingQualifierHandler();
                AnnotatedEventHandlingComponent<Object> component = new AnnotatedEventHandlingComponent<>(
                        handler,
                        context.getBean(ParameterResolverFactory.class),
                        context.getBean(HandlerDefinition.class),
                        context.getBean(MessageTypeResolver.class),
                        context.getBean(EventConverter.class)
                );
                EventMessage event = new GenericEventMessage(new MessageType(String.class), "test-event");

                // when
                component.handle(event, new StubProcessingContext().withMessage(event))
                         .asCompletableFuture()
                         .orTimeout(5, TimeUnit.SECONDS)
                         .join();

                // then
                assertThat(handler.greeter).isSameAs(context.getBean("primaryGreeter"));
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

    static class GreeterContext {

        @Primary
        @Bean
        public Greeter primaryGreeter() {
            return new Greeter("primary");
        }

        @Qualifier("special")
        @Bean
        public Greeter specialGreeter() {
            return new Greeter("special");
        }
    }

    static class GreetingHandlerContext extends GreeterContext {

        @Bean
        public GreetingHandler greetingHandler() {
            return new GreetingHandler();
        }
    }

    static class MissingGreeterHandlerContext extends GreeterContext {

        @Bean
        public MissingGreeterHandler missingGreeterHandler() {
            return new MissingGreeterHandler();
        }
    }

    record Greeter(String greeting) {

    }

    @SuppressWarnings("unused")
    static class GreetingHandler {

        private final CountDownLatch handled = new CountDownLatch(1);
        private volatile Greeter unannotated;
        private volatile Greeter byBeanName;
        private volatile Greeter byQualifier;
        private volatile Greeter conflictingQualifier;

        @EventHandler
        void on(String event,
                Greeter unannotated,
                @InjectComponent(name = "specialGreeter") Greeter byBeanName,
                @InjectComponent(name = "special") Greeter byQualifier,
                @InjectComponent(name = "primaryGreeter") @Qualifier("special") Greeter conflictingQualifier) {
            this.unannotated = unannotated;
            this.byBeanName = byBeanName;
            this.byQualifier = byQualifier;
            this.conflictingQualifier = conflictingQualifier;
            handled.countDown();
        }
    }

    @SuppressWarnings("unused")
    static class MissingGreeterHandler {

        @EventHandler
        void on(String event, @InjectComponent(name = "missingGreeter") Greeter greeter) {
        }
    }

    @SuppressWarnings("unused")
    static class ConflictingQualifierHandler {

        private volatile Greeter greeter;

        @EventHandler
        void on(String event, @InjectComponent(name = "primaryGreeter") @Qualifier("special") Greeter greeter) {
            this.greeter = greeter;
        }
    }
}
