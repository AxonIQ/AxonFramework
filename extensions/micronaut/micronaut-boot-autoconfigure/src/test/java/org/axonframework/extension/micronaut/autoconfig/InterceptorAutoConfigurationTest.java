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

import jakarta.annotation.Nonnull;
import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.commandhandling.gateway.CommandGateway;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.axonframework.messaging.eventhandling.gateway.EventGateway;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.TokenStore;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.inmemory.InMemoryTokenStore;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageDispatchInterceptor;
import org.axonframework.messaging.core.MessageDispatchInterceptorChain;
import org.axonframework.messaging.core.MessageHandlerInterceptor;
import org.axonframework.messaging.core.MessageHandlerInterceptorChain;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.queryhandling.gateway.QueryGateway;
import org.axonframework.messaging.queryhandling.QueryMessage;
import org.axonframework.messaging.queryhandling.annotation.QueryHandler;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.EnableMBeanExport;
import org.springframework.core.annotation.Order;
import org.springframework.jmx.support.RegistrationPolicy;
import org.springframework.test.context.ContextConfiguration;

import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test class validating the behavior of the {@link InfrastructureAutoConfiguration}.
 *
 * @author Christian Thiel
 */
class InterceptorAutoConfigurationTest {

    private ApplicationContextRunner testApplicationContext;

    @BeforeEach
    void setUp() {
        testApplicationContext = new ApplicationContextRunner()
                .withUserConfiguration(DefaultContext.class)
                .withPropertyValues("axon.axonserver.enabled:false", "axon.eventstorage.jpa.polling-interval:0");
    }

    @Test
    @Disabled("TODO # #3485 - Ordering of interceptors is not supported")
    public void commandHandlerInterceptorsAreRegisteredInCorrectOrder() {
        testApplicationContext.withUserConfiguration(MessageInterceptorContext.class).run(context -> {
            context.getBean(CommandGateway.class).sendAndWait(new Object());
            //noinspection unchecked
            Queue<String> commandHandlingInterceptingOutcome =
                    context.getBean("commandHandlingInterceptingOutcome", Queue.class);
            //noinspection unchecked
            Queue<String> queryHandlingInterceptingOutcome =
                    context.getBean("queryHandlingInterceptingOutcome", Queue.class);
            //noinspection unchecked
            Queue<String> eventHandlingInterceptingOutcome =
                    context.getBean("eventHandlingInterceptingOutcome", Queue.class);

            assertThat(queryHandlingInterceptingOutcome).isEmpty();
            assertThat(eventHandlingInterceptingOutcome).isEmpty();
            assertThat(commandHandlingInterceptingOutcome).hasSize(3);
            assertThat(commandHandlingInterceptingOutcome.poll()).startsWith("Order-0");
            assertThat(commandHandlingInterceptingOutcome.poll()).startsWith("Order-100");
            assertThat(commandHandlingInterceptingOutcome.poll()).startsWith("Unordered");
        });
    }

    @Test
    @Disabled("TODO # #3485 - Ordering of interceptors is not supported")
    public void queryHandlerInterceptorsAreRegisteredInCorrectOrder() {
        testApplicationContext.withUserConfiguration(MessageInterceptorContext.class).run(context -> {
            context.getBean(QueryGateway.class).query("foo", String.class, null);
            //noinspection unchecked
            Queue<String> commandHandlingInterceptingOutcome =
                    context.getBean("commandHandlingInterceptingOutcome", Queue.class);
            //noinspection unchecked
            Queue<String> queryHandlingInterceptingOutcome =
                    context.getBean("queryHandlingInterceptingOutcome", Queue.class);
            //noinspection unchecked
            Queue<String> eventHandlingInterceptingOutcome =
                    context.getBean("eventHandlingInterceptingOutcome", Queue.class);

            assertThat(commandHandlingInterceptingOutcome).isEmpty();
            assertThat(eventHandlingInterceptingOutcome).isEmpty();
            assertThat(queryHandlingInterceptingOutcome).hasSize(3);

            assertThat(queryHandlingInterceptingOutcome.poll()).startsWith("Order-0");
            assertThat(queryHandlingInterceptingOutcome.poll()).startsWith("Order-100");
            assertThat(queryHandlingInterceptingOutcome.poll()).startsWith("Unordered");
        });
    }

    @Test
    @Disabled("TODO # #3485 - Ordering of interceptors is not supported")
    public void eventHandlerInterceptorsAreRegisteredInCorrectOrder() {
        testApplicationContext.withUserConfiguration(MessageInterceptorContext.class).run(context -> {
            context.getBean(EventGateway.class).publish(null, "foo");

            // Wait for all the event handlers to had their chance.
            CountDownLatch eventHandlerInterceptorInvocations = context.getBean("eventHandlerInterceptorInvocations",
                                                                                CountDownLatch.class);
            assertThat(eventHandlerInterceptorInvocations.await(1, TimeUnit.SECONDS)).isTrue();

            //noinspection unchecked
            Queue<String> commandHandlingInterceptingOutcome =
                    context.getBean("commandHandlingInterceptingOutcome", Queue.class);
            //noinspection unchecked
            Queue<String> queryHandlingInterceptingOutcome =
                    context.getBean("queryHandlingInterceptingOutcome", Queue.class);
            //noinspection unchecked
            Queue<String> eventHandlingInterceptingOutcome =
                    context.getBean("eventHandlingInterceptingOutcome", Queue.class);

            assertThat(commandHandlingInterceptingOutcome).isEmpty();
            assertThat(queryHandlingInterceptingOutcome).isEmpty();
            assertThat(eventHandlingInterceptingOutcome).hasSize(3);
            assertThat(eventHandlingInterceptingOutcome.poll()).startsWith("Order-0");
            assertThat(eventHandlingInterceptingOutcome.poll()).startsWith("Order-100");
            assertThat(eventHandlingInterceptingOutcome.poll()).startsWith("Unordered");
        });
    }

    @Test
    public void commandDispatchInterceptorsAreRegisteredInCorrectOrder() {
        testApplicationContext.withUserConfiguration(MessageInterceptorContext.class).run(context -> {
            context.getBean(CommandGateway.class).sendAndWait(new Object());
            //noinspection unchecked
            Queue<String> commandDispatchingInterceptingOutcome =
                    context.getBean("commandDispatchingInterceptingOutcome", Queue.class);
            //noinspection unchecked
            Queue<String> queryDispatchingInterceptingOutcome =
                    context.getBean("queryDispatchingInterceptingOutcome", Queue.class);
            //noinspection unchecked
            Queue<String> eventDispatchingInterceptingOutcome =
                    context.getBean("eventDispatchingInterceptingOutcome", Queue.class);

            assertThat(queryDispatchingInterceptingOutcome).isEmpty();
            assertThat(eventDispatchingInterceptingOutcome).isEmpty();
            assertThat(commandDispatchingInterceptingOutcome).hasSize(3);
            assertThat(commandDispatchingInterceptingOutcome.poll()).startsWith("Order-0");
            assertThat(commandDispatchingInterceptingOutcome.poll()).startsWith("Order-100");
            assertThat(commandDispatchingInterceptingOutcome.poll()).startsWith("Unordered");
        });
    }

    @Test
    public void eventDispatchInterceptorsAreRegisteredInCorrectOrder() {
        testApplicationContext.withUserConfiguration(MessageInterceptorContext.class).run(context -> {
            context.getBean(EventGateway.class).publish(null, "foo");
            //noinspection unchecked
            Queue<String> commandDispatchingInterceptingOutcome =
                    context.getBean("commandDispatchingInterceptingOutcome", Queue.class);
            //noinspection unchecked
            Queue<String> queryDispatchingInterceptingOutcome =
                    context.getBean("queryDispatchingInterceptingOutcome", Queue.class);
            //noinspection unchecked
            Queue<String> eventDispatchingInterceptingOutcome =
                    context.getBean("eventDispatchingInterceptingOutcome", Queue.class);

            assertThat(queryDispatchingInterceptingOutcome).isEmpty();
            assertThat(commandDispatchingInterceptingOutcome).isEmpty();
            assertThat(eventDispatchingInterceptingOutcome).hasSize(3);
            assertThat(eventDispatchingInterceptingOutcome.poll()).startsWith("Order-0");
            assertThat(eventDispatchingInterceptingOutcome.poll()).startsWith("Order-100");
            assertThat(eventDispatchingInterceptingOutcome.poll()).startsWith("Unordered");
        });
    }

    @Test
    public void queryDispatchInterceptorsAreRegisteredInCorrectOrder() {
        testApplicationContext.withUserConfiguration(MessageInterceptorContext.class).run(context -> {
            context.getBean(QueryGateway.class).query("foo", String.class, null);
            //noinspection unchecked
            Queue<String> commandDispatchingInterceptingOutcome =
                    context.getBean("commandDispatchingInterceptingOutcome", Queue.class);
            //noinspection unchecked
            Queue<String> queryDispatchingInterceptingOutcome =
                    context.getBean("queryDispatchingInterceptingOutcome", Queue.class);
            //noinspection unchecked
            Queue<String> eventDispatchingInterceptingOutcome =
                    context.getBean("eventDispatchingInterceptingOutcome", Queue.class);

            assertThat(commandDispatchingInterceptingOutcome).isEmpty();
            assertThat(eventDispatchingInterceptingOutcome).isEmpty();
            assertThat(queryDispatchingInterceptingOutcome).hasSize(3);
            assertThat(queryDispatchingInterceptingOutcome.poll()).startsWith("Order-0");
            assertThat(queryDispatchingInterceptingOutcome.poll()).startsWith("Order-100");
            assertThat(queryDispatchingInterceptingOutcome.poll()).startsWith("Unordered");
        });
    }

    @Test
    public void startsWithSingleInterceptor() {
        testApplicationContext.withUserConfiguration(SlimMessageInterceptorContext.class).run(context -> {
            // We have to access the interceptor to trigger lazy initialization
            context.getBean(SlimMessageInterceptorContext.MyCommandHandlerInterceptor.class);
        });
    }

    @ContextConfiguration
    @EnableAutoConfiguration
    @EnableMBeanExport(registration = RegistrationPolicy.IGNORE_EXISTING)
    static class DefaultContext {

        @Bean
        public TokenStore tokenStore() {
            return new InMemoryTokenStore();
        }
    }

    static class MessageInterceptorContext {

        @Bean
        public CountDownLatch commandHandlerInterceptorInvocations() {
            return new CountDownLatch(3);
        }

        @Bean
        public Queue<String> commandHandlingInterceptingOutcome() {
            return new LinkedList<>();
        }

        @Bean
        public CountDownLatch queryHandlerInterceptorInvocations() {
            return new CountDownLatch(3);
        }

        @Bean
        public Queue<String> queryHandlingInterceptingOutcome() {
            return new LinkedList<>();
        }

        @Bean
        public CountDownLatch eventHandlerInterceptorInvocations() {
            return new CountDownLatch(3);
        }

        @Bean
        public Queue<String> eventHandlingInterceptingOutcome() {
            return new LinkedList<>();
        }

        @Bean
        public CountDownLatch commandDispatchInterceptorInvocations() {
            return new CountDownLatch(3);
        }

        @Bean
        public Queue<String> commandDispatchingInterceptingOutcome() {
            return new LinkedList<>();
        }

        @Bean
        public CountDownLatch queryDispatchInterceptorInvocations() {
            return new CountDownLatch(3);
        }

        @Bean
        public Queue<String> queryDispatchingInterceptingOutcome() {
            return new LinkedList<>();
        }

        @Bean
        public CountDownLatch eventDispatchInterceptorInvocations() {
            return new CountDownLatch(3);
        }

        @Bean
        public Queue<String> eventDispatchingInterceptingOutcome() {
            return new LinkedList<>();
        }

        @Bean
        @Order(100)
        public MyGenericHandlerInterceptor myFirstGenericHandlerInterceptor(
                @Qualifier("commandHandlerInterceptorInvocations") CountDownLatch commandHandlerInterceptorInvocations,
                @Qualifier("queryHandlerInterceptorInvocations") CountDownLatch queryHandlerInterceptorInvocations,
                @Qualifier("eventHandlerInterceptorInvocations") CountDownLatch eventHandlerInterceptorInvocations,
                @Qualifier("commandHandlingInterceptingOutcome") Queue<String> commandHandlingInterceptingOutcome,
                @Qualifier("queryHandlingInterceptingOutcome") Queue<String> queryHandlingInterceptingOutcome,
                @Qualifier("eventHandlingInterceptingOutcome") Queue<String> eventHandlingInterceptingOutcome
        ) {
            return new MyGenericHandlerInterceptor("Order-100",
                                                   commandHandlerInterceptorInvocations,
                                                   queryHandlerInterceptorInvocations,
                                                   eventHandlerInterceptorInvocations,
                                                   commandHandlingInterceptingOutcome,
                                                   queryHandlingInterceptingOutcome,
                                                   eventHandlingInterceptingOutcome);
        }

        @Bean
        @Order(0)
        public MyCommandHandlerInterceptor mySecondCommandHandlerInterceptor(
                @Qualifier("commandHandlerInterceptorInvocations") CountDownLatch commandHandlerInterceptorInvocations,
                @Qualifier("commandHandlingInterceptingOutcome") Queue<String> commandHandlingInterceptingOutcome
        ) {
            return new MyCommandHandlerInterceptor("Order-0",
                                                   commandHandlerInterceptorInvocations,
                                                   commandHandlingInterceptingOutcome);
        }

        @Bean
        public MyCommandHandlerInterceptor myThirdCommandHandlerInterceptor(
                @Qualifier("commandHandlerInterceptorInvocations") CountDownLatch commandHandlerInterceptorInvocations,
                @Qualifier("commandHandlingInterceptingOutcome") Queue<String> commandHandlingInterceptingOutcome
        ) {
            return new MyCommandHandlerInterceptor("Unordered",
                                                   commandHandlerInterceptorInvocations,
                                                   commandHandlingInterceptingOutcome);
        }

        @Bean
        @Order(0)
        public MyQueryHandlerInterceptor mySecondQueryHandlerInterceptor(
                @Qualifier("queryHandlerInterceptorInvocations") CountDownLatch queryHandlerInterceptorInvocations,
                @Qualifier("queryHandlingInterceptingOutcome") Queue<String> queryHandlingInterceptingOutcome
        ) {
            return new MyQueryHandlerInterceptor("Order-0",
                                                 queryHandlerInterceptorInvocations,
                                                 queryHandlingInterceptingOutcome);
        }

        @Bean
        public MyQueryHandlerInterceptor myThirdQueryHandlerInterceptor(
                @Qualifier("queryHandlerInterceptorInvocations") CountDownLatch queryHandlerInterceptorInvocations,
                @Qualifier("queryHandlingInterceptingOutcome") Queue<String> queryHandlingInterceptingOutcome
        ) {
            return new MyQueryHandlerInterceptor("Unordered",
                                                 queryHandlerInterceptorInvocations,
                                                 queryHandlingInterceptingOutcome);
        }

        @Bean
        @Order(0)
        public MyEventHandlerInterceptor mySecondEventHandlerInterceptor(
                @Qualifier("eventHandlerInterceptorInvocations") CountDownLatch eventHandlerInterceptorInvocations,
                @Qualifier("eventHandlingInterceptingOutcome") Queue<String> eventHandlingInterceptingOutcome
        ) {
            return new MyEventHandlerInterceptor("Order-0",
                                                 eventHandlerInterceptorInvocations,
                                                 eventHandlingInterceptingOutcome);
        }

        @Bean
        public MyEventHandlerInterceptor myThirdEventHandlerInterceptor(
                @Qualifier("eventHandlerInterceptorInvocations") CountDownLatch eventHandlerInterceptorInvocations,
                @Qualifier("eventHandlingInterceptingOutcome") Queue<String> eventHandlingInterceptingOutcome
        ) {
            return new MyEventHandlerInterceptor("Unordered",
                                                 eventHandlerInterceptorInvocations,
                                                 eventHandlingInterceptingOutcome);
        }


        @Bean
        @Order(100)
        public MyGenericDispatchInterceptor<Message> myFirstGenericDispatcherInterceptor(
                @Qualifier("commandDispatchInterceptorInvocations") CountDownLatch commandDispatchInterceptorInvocations,
                @Qualifier("queryDispatchInterceptorInvocations") CountDownLatch queryDispatchInterceptorInvocations,
                @Qualifier("eventDispatchInterceptorInvocations") CountDownLatch eventDispatchInterceptorInvocations,
                @Qualifier("commandDispatchingInterceptingOutcome") Queue<String> commandDispatchingInterceptingOutcome,
                @Qualifier("queryDispatchingInterceptingOutcome") Queue<String> queryDispatchingInterceptingOutcome,
                @Qualifier("eventDispatchingInterceptingOutcome") Queue<String> eventDispatchingInterceptingOutcome
        ) {
            return new MyGenericDispatchInterceptor<>("Order-100",
                                                      commandDispatchInterceptorInvocations,
                                                      queryDispatchInterceptorInvocations,
                                                      eventDispatchInterceptorInvocations,
                                                      commandDispatchingInterceptingOutcome,
                                                      queryDispatchingInterceptingOutcome,
                                                      eventDispatchingInterceptingOutcome);
        }

        @Bean
        @Order(0)
        public MyCommandDispatchInterceptor mySecondCommandDispatchInterceptor(
                @Qualifier("commandDispatchInterceptorInvocations") CountDownLatch commandDispatchInterceptorInvocations,
                @Qualifier("commandDispatchingInterceptingOutcome") Queue<String> commandDispatchingInterceptingOutcome
        ) {
            return new MyCommandDispatchInterceptor("Order-0",
                                                    commandDispatchInterceptorInvocations,
                                                    commandDispatchingInterceptingOutcome);
        }

        @Bean
        public MyCommandDispatchInterceptor myThirdCommandDispatchInterceptor(
                @Qualifier("commandDispatchInterceptorInvocations") CountDownLatch commandDispatchInterceptorInvocations,
                @Qualifier("commandDispatchingInterceptingOutcome") Queue<String> commandDispatchingInterceptingOutcome
        ) {
            return new MyCommandDispatchInterceptor("Unordered",
                                                    commandDispatchInterceptorInvocations,
                                                    commandDispatchingInterceptingOutcome);
        }

        @Bean
        @Order(0)
        public MyQueryDispatchInterceptor mySecondQueryDispatchInterceptor(
                @Qualifier("queryDispatchInterceptorInvocations") CountDownLatch queryDispatchInterceptorInvocations,
                @Qualifier("queryDispatchingInterceptingOutcome") Queue<String> queryDispatchingInterceptingOutcome
        ) {
            return new MyQueryDispatchInterceptor("Order-0",
                                                  queryDispatchInterceptorInvocations,
                                                  queryDispatchingInterceptingOutcome);
        }

        @Bean
        public MyQueryDispatchInterceptor myThirdQueryDispatchInterceptor(
                @Qualifier("queryDispatchInterceptorInvocations") CountDownLatch queryDispatchInterceptorInvocations,
                @Qualifier("queryDispatchingInterceptingOutcome") Queue<String> queryDispatchingInterceptingOutcome
        ) {
            return new MyQueryDispatchInterceptor("Unordered",
                                                  queryDispatchInterceptorInvocations,
                                                  queryDispatchingInterceptingOutcome);
        }

        @Bean
        @Order(0)
        public MyEventDispatchInterceptor mySecondEventDispatchInterceptor(
                @Qualifier("eventDispatchInterceptorInvocations") CountDownLatch eventDispatchInterceptorInvocations,
                @Qualifier("eventDispatchingInterceptingOutcome") Queue<String> eventDispatchingInterceptingOutcome
        ) {
            return new MyEventDispatchInterceptor("Order-0",
                                                  eventDispatchInterceptorInvocations,
                                                  eventDispatchingInterceptingOutcome);
        }

        @Bean
        public MyEventDispatchInterceptor myThirdEventDispatchInterceptor(
                @Qualifier("eventDispatchInterceptorInvocations") CountDownLatch eventDispatchInterceptorInvocations,
                @Qualifier("eventDispatchingInterceptingOutcome") Queue<String> eventDispatchingInterceptingOutcome
        ) {
            return new MyEventDispatchInterceptor("Unordered",
                                                  eventDispatchInterceptorInvocations,
                                                  eventDispatchingInterceptingOutcome);
        }

        @Bean
        public CommandHandlingComponent commandHandlingComponent() {
            return new CommandHandlingComponent();
        }

        @Bean
        public QueryHandlingComponent queryHandlingComponent() {
            return new QueryHandlingComponent();
        }

        @Bean
        public EventHandlingComponent eventHandlingComponent() {
            return new EventHandlingComponent();
        }

        public static class MyCommandHandlerInterceptor extends MyHandlerInterceptor<CommandMessage> {

            public MyCommandHandlerInterceptor(String name, CountDownLatch invocation, Queue<String> handlingOutcome) {
                super(name, invocation, handlingOutcome);
            }
        }

        public static class MyQueryHandlerInterceptor extends MyHandlerInterceptor<QueryMessage> {

            public MyQueryHandlerInterceptor(String name, CountDownLatch invocation, Queue<String> handlingOutcome) {
                super(name, invocation, handlingOutcome);
            }
        }

        public static class MyEventHandlerInterceptor extends MyHandlerInterceptor<EventMessage> {

            public MyEventHandlerInterceptor(String name, CountDownLatch invocation, Queue<String> handlingOutcome) {
                super(name, invocation, handlingOutcome);
            }
        }

        public static class MyCommandDispatchInterceptor extends MyDispatchInterceptor<CommandMessage> {

            public MyCommandDispatchInterceptor(String name, CountDownLatch invocation, Queue<String> handlingOutcome) {
                super(name, invocation, handlingOutcome);
            }
        }

        public static class MyQueryDispatchInterceptor extends MyDispatchInterceptor<QueryMessage> {

            public MyQueryDispatchInterceptor(String name, CountDownLatch invocation, Queue<String> handlingOutcome) {
                super(name, invocation, handlingOutcome);
            }
        }

        public static class MyEventDispatchInterceptor extends MyDispatchInterceptor<EventMessage> {

            public MyEventDispatchInterceptor(String name, CountDownLatch invocation, Queue<String> handlingOutcome) {
                super(name, invocation, handlingOutcome);
            }
        }

        public static class MyHandlerInterceptor<T extends Message> implements MessageHandlerInterceptor<T> {

            private final String name;
            private final CountDownLatch invocation;
            private final Queue<String> handlingOutcome;

            public MyHandlerInterceptor(String name,
                                        CountDownLatch invocation,
                                        Queue<String> handlingOutcome) {
                this.name = name;
                this.invocation = invocation;
                this.handlingOutcome = handlingOutcome;
            }

            @Override
            @Nonnull
            public MessageStream<?> interceptOnHandle(@Nonnull T message,
                                                      @Nonnull ProcessingContext context,
                                                      @Nonnull MessageHandlerInterceptorChain<T> interceptorChain) {
                interceptMessage(message);
                return interceptorChain.proceed(message, context);
            }

            private void interceptMessage(T message) {
                invocation.countDown();
                handlingOutcome.add(name + ": " + message);
            }
        }

        public static class MyDispatchInterceptor<T extends Message> implements MessageDispatchInterceptor<T> {

            private final String name;
            private final CountDownLatch invocation;
            private final Queue<String> handlingOutcome;

            public MyDispatchInterceptor(String name, CountDownLatch invocation, Queue<String> handlingOutcome) {
                this.name = name;
                this.invocation = invocation;
                this.handlingOutcome = handlingOutcome;
            }

            @Override
            public @NotNull MessageStream<?> interceptOnDispatch(@NotNull T message,
                                                                 @Nullable ProcessingContext context,
                                                                 @NotNull MessageDispatchInterceptorChain<T> interceptorChain) {
                invocation.countDown();
                handlingOutcome.add(name + ": " + message);
                return interceptorChain.proceed(message, context);
            }
        }

        public static class MyGenericDispatchInterceptor<T extends Message>
                implements MessageDispatchInterceptor<T> {

            private final String name;
            private final CountDownLatch commandInvocation;
            private final CountDownLatch queryInvocation;
            private final CountDownLatch eventInvocation;
            private final Queue<String> commandHandlingOutcome;
            private final Queue<String> queryHandlingOutcome;
            private final Queue<String> eventHandlingOutcome;

            public MyGenericDispatchInterceptor(String name,
                                                CountDownLatch commandInvocation,
                                                CountDownLatch queryInvocation,
                                                CountDownLatch eventInvocation,
                                                Queue<String> commandHandlingOutcome,
                                                Queue<String> queryHandlingOutcome,
                                                Queue<String> eventHandlingOutcome) {
                this.name = name;
                this.commandInvocation = commandInvocation;
                this.queryInvocation = queryInvocation;
                this.eventInvocation = eventInvocation;
                this.commandHandlingOutcome = commandHandlingOutcome;
                this.queryHandlingOutcome = queryHandlingOutcome;
                this.eventHandlingOutcome = eventHandlingOutcome;
            }

            @Override
            public @NotNull MessageStream<?> interceptOnDispatch(@NotNull T message,
                                                                 @Nullable ProcessingContext context,
                                                                 @NotNull MessageDispatchInterceptorChain<T> interceptorChain) {
                if (message instanceof CommandMessage) {
                    commandInvocation.countDown();
                    commandHandlingOutcome.add(name);
                } else if (message instanceof QueryMessage) {
                    queryInvocation.countDown();
                    queryHandlingOutcome.add(name);
                } else {
                    eventInvocation.countDown();
                    eventHandlingOutcome.add(name);
                }
                return interceptorChain.proceed(message, context);
            }
        }

        public static class MyGenericHandlerInterceptor implements MessageHandlerInterceptor<Message> {

            private final String name;
            private final CountDownLatch commandInvocation;
            private final CountDownLatch queryInvocation;
            private final CountDownLatch eventInvocation;
            private final Queue<String> commandHandlingOutcome;
            private final Queue<String> queryHandlingOutcome;
            private final Queue<String> eventHandlingOutcome;

            public MyGenericHandlerInterceptor(String name,
                                               CountDownLatch commandInvocation,
                                               CountDownLatch queryInvocation,
                                               CountDownLatch eventInvocation,
                                               Queue<String> commandHandlingOutcome,
                                               Queue<String> queryHandlingOutcome,
                                               Queue<String> eventHandlingOutcome) {
                this.name = name;
                this.commandInvocation = commandInvocation;
                this.queryInvocation = queryInvocation;
                this.eventInvocation = eventInvocation;
                this.commandHandlingOutcome = commandHandlingOutcome;
                this.queryHandlingOutcome = queryHandlingOutcome;
                this.eventHandlingOutcome = eventHandlingOutcome;
            }

            @Override
            @Nonnull
            public MessageStream<?> interceptOnHandle(@Nonnull Message message,
                                                      @Nonnull ProcessingContext context,
                                                      @Nonnull MessageHandlerInterceptorChain<Message> interceptorChain) {
                if (message instanceof CommandMessage) {
                    commandInvocation.countDown();
                    commandHandlingOutcome.add(name);
                } else if (message instanceof QueryMessage) {
                    queryInvocation.countDown();
                    queryHandlingOutcome.add(name);
                } else {
                    eventInvocation.countDown();
                    eventHandlingOutcome.add(name);
                }
                return interceptorChain.proceed(message, context);
            }
        }


        @SuppressWarnings("unused")
        static class CommandHandlingComponent {

            @CommandHandler
            public void handle(Object command) {
            }
        }

        @SuppressWarnings("unused")
        static class QueryHandlingComponent {

            @QueryHandler
            public String handle(String query) {
                return "bar";
            }
        }

        @SuppressWarnings("unused")
        static class EventHandlingComponent {

            @EventHandler
            public void handle(String event) {
            }
        }
    }

    static class SlimMessageInterceptorContext {

        @Bean
        public MyCommandHandlerInterceptor myCommandHandlerInterceptor() {
            return new MyCommandHandlerInterceptor();
        }

        public static class MyCommandHandlerInterceptor implements MessageHandlerInterceptor<CommandMessage> {

            @Nonnull
            @Override
            public @NotNull MessageStream<?> interceptOnHandle(@NotNull CommandMessage message,
                                                               @NotNull ProcessingContext context,
                                                               @NotNull MessageHandlerInterceptorChain<CommandMessage> interceptorChain) {
                return interceptorChain.proceed(message, context);
            }
        }
    }
}
