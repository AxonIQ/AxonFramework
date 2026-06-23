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

package org.axonframework.messaging.core;

import org.awaitility.Awaitility;
import org.axonframework.conversion.PassThroughConverter;
import org.axonframework.messaging.commandhandling.CommandBus;
import org.axonframework.messaging.commandhandling.CommandBusTestUtils;
import org.axonframework.messaging.commandhandling.CommandPriorityCalculator;
import org.axonframework.messaging.commandhandling.CommandResultMessage;
import org.axonframework.messaging.commandhandling.GenericCommandResultMessage;
import org.axonframework.messaging.commandhandling.annotation.AnnotatedCommandHandlingComponent;
import org.axonframework.messaging.commandhandling.annotation.AnnotationRoutingStrategy;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.commandhandling.gateway.CommandGateway;
import org.axonframework.messaging.commandhandling.gateway.DefaultCommandGateway;
import org.axonframework.messaging.core.annotation.AnnotationMessageTypeResolver;
import org.axonframework.messaging.core.annotation.ClasspathHandlerDefinition;
import org.axonframework.messaging.core.annotation.ClasspathParameterResolverFactory;
import org.axonframework.messaging.core.annotation.DefaultParameterResolverFactory;
import org.axonframework.messaging.core.annotation.ParameterResolverFactory;
import org.axonframework.messaging.core.conversion.DelegatingMessageConverter;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.StubProcessingContext;
import org.axonframework.messaging.eventhandling.EventBus;
import org.axonframework.messaging.eventhandling.GenericEventMessage;
import org.axonframework.messaging.eventhandling.SimpleEventBus;
import org.axonframework.messaging.eventhandling.annotation.AnnotatedEventHandlingComponent;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.axonframework.messaging.eventhandling.conversion.DelegatingEventConverter;
import org.axonframework.messaging.eventhandling.conversion.EventConverter;
import org.axonframework.messaging.queryhandling.GenericQueryResponseMessage;
import org.axonframework.messaging.queryhandling.QueryBus;
import org.axonframework.messaging.queryhandling.QueryBusTestUtils;
import org.axonframework.messaging.queryhandling.QueryPriorityCalculator;
import org.axonframework.messaging.queryhandling.annotation.AnnotatedQueryHandlingComponent;
import org.axonframework.messaging.queryhandling.annotation.QueryHandler;
import org.axonframework.messaging.queryhandling.gateway.DefaultQueryGateway;
import org.axonframework.messaging.queryhandling.gateway.QueryGateway;
import org.junit.jupiter.api.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Test class validating asynchronous return types are supported for command, event, and query handlers.
 *
 * @author John Hendrikx
 */
class AsyncMessageHandlerTest {

    private static final ParameterResolverFactory PARAMETER_RESOLVER_FACTORY = new DefaultParameterResolverFactory();

    private final CommandBus commandBus = CommandBusTestUtils.aCommandBus();
    private final CommandGateway commandGateway = new DefaultCommandGateway(commandBus,
                                                                            new ClassBasedMessageTypeResolver(),
                                                                            CommandPriorityCalculator.defaultCalculator(),
                                                                            new AnnotationRoutingStrategy());
    private final QueryBus queryBus = QueryBusTestUtils.aQueryBus();
    private final QueryGateway queryGateway = new DefaultQueryGateway(
            queryBus,
            new ClassBasedMessageTypeResolver(),
            QueryPriorityCalculator.defaultCalculator(),
            new DelegatingMessageConverter(PassThroughConverter.INSTANCE)
    );
    private final EventBus eventBus = new SimpleEventBus();
    private final AtomicBoolean eventHandlerCalled = new AtomicBoolean();

    record CheckIfPrime(int value) {
        // command
    }

    record GetKnownPrimes() {
        // query
    }

    record EchoValue(int... values) {
        // query
    }

    record PrimeChecked(int value) {
        // event
    }

    @Nested
    class AnnotationBased {

        @Nested
        class EventHandlers {

            @Test
            void withVoidReturnTypeShouldBeCalled() {
                var ehc = new AnnotatedEventHandlingComponent<>(
                        new VoidEventHandler(),
                        ClasspathParameterResolverFactory.forClass(VoidEventHandler.class),
                        ClasspathHandlerDefinition.forClass(VoidEventHandler.class),
                        new AnnotationMessageTypeResolver(),
                        new DelegatingEventConverter(PassThroughConverter.INSTANCE)
                );
                ProcessingContext testContext =
                        StubProcessingContext.withComponent(EventConverter.class,
                                                            new DelegatingEventConverter(PassThroughConverter.INSTANCE));

                eventBus.subscribe((messages, context) -> {
                    messages.forEach(m -> ehc.handle(m, testContext));
                    return CompletableFuture.completedFuture(null);
                });

                assertEvents();
            }

            @Test
            void returningMonoShouldExecuteIt() {
                var ehc = new AnnotatedEventHandlingComponent<>(
                        new MonoEventHandler(),
                        ClasspathParameterResolverFactory.forClass(MonoEventHandler.class),
                        ClasspathHandlerDefinition.forClass(MonoEventHandler.class),
                        new AnnotationMessageTypeResolver(),
                        new DelegatingEventConverter(PassThroughConverter.INSTANCE)
                );
                ProcessingContext testContext =
                        StubProcessingContext.withComponent(EventConverter.class,
                                                            new DelegatingEventConverter(PassThroughConverter.INSTANCE));

                eventBus.subscribe((messages, context) -> {
                    messages.forEach(m -> ehc.handle(m, testContext));
                    return CompletableFuture.completedFuture(null);
                });

                assertEvents();
            }

            @Test
            void returningCompletableFutureShouldExecuteIt() {
                var ehc = new AnnotatedEventHandlingComponent<>(
                        new CompletableFutureEventHandler(),
                        ClasspathParameterResolverFactory.forClass(CompletableFutureEventHandler.class),
                        ClasspathHandlerDefinition.forClass(CompletableFutureEventHandler.class),
                        new AnnotationMessageTypeResolver(),
                        new DelegatingEventConverter(PassThroughConverter.INSTANCE)
                );
                ProcessingContext testContext =
                        StubProcessingContext.withComponent(EventConverter.class,
                                                            new DelegatingEventConverter(PassThroughConverter.INSTANCE));

                eventBus.subscribe((messages, context) -> {
                    messages.forEach(m -> ehc.handle(m, testContext));
                    return CompletableFuture.completedFuture(null);
                });

                assertEvents();
            }
        }

        @Nested
        class CommandHandlers {

            @Test
            void returningCompletableFutureShouldUseItsResult() {
                commandBus.subscribe(new AnnotatedCommandHandlingComponent<>(
                        new CompletableFutureCommandHandler(),
                        ClasspathParameterResolverFactory.forClass(CompletableFutureCommandHandler.class),
                        ClasspathHandlerDefinition.forClass(CompletableFutureCommandHandler.class),
                        new AnnotationMessageTypeResolver(),
                        new DelegatingMessageConverter(PassThroughConverter.INSTANCE)
                ));

                assertCommands();
            }

            @Test
            void returningMonoShouldUseItsResult() {
                commandBus.subscribe(new AnnotatedCommandHandlingComponent<>(
                        new MonoCommandHandler(),
                        ClasspathParameterResolverFactory.forClass(MonoCommandHandler.class),
                        ClasspathHandlerDefinition.forClass(MonoCommandHandler.class),
                        new AnnotationMessageTypeResolver(),
                        new DelegatingMessageConverter(PassThroughConverter.INSTANCE)
                ));

                assertCommands();
            }

            @Test
            void returningBooleanShouldUseResult() {
                commandBus.subscribe(new AnnotatedCommandHandlingComponent<>(
                        new BooleanCommandHandler(),
                        ClasspathParameterResolverFactory.forClass(BooleanCommandHandler.class),
                        ClasspathHandlerDefinition.forClass(BooleanCommandHandler.class),
                        new AnnotationMessageTypeResolver(),
                        new DelegatingMessageConverter(PassThroughConverter.INSTANCE)
                ));

                assertCommands();
            }
        }

        @Nested
        class QueryHandlers {

            @Test
            void returningJustShouldUseResult() {
                int echoInt = 1;
                JustQueryHandler handler = new JustQueryHandler();
                queryBus.subscribe(new AnnotatedQueryHandlingComponent<>(
                        handler,
                        ClasspathParameterResolverFactory.forClass(handler.getClass()),
                        ClasspathHandlerDefinition.forClass(handler.getClass()),
                        new AnnotationMessageTypeResolver(),
                        new DelegatingMessageConverter(PassThroughConverter.INSTANCE)
                ));

                CompletableFuture<Integer> result = queryGateway.query(new EchoValue(echoInt), Integer.class, null);
                assertThat(result).isDone();
                assertThat(result.join()).isEqualTo(echoInt);
            }

            @Test
            void returningPrimitiveShouldUseResult() {
                int echoInt = 1;
                PrimitiveQueryHandler handler = new PrimitiveQueryHandler();
                queryBus.subscribe(new AnnotatedQueryHandlingComponent<>(
                        handler,
                        ClasspathParameterResolverFactory.forClass(handler.getClass()),
                        ClasspathHandlerDefinition.forClass(handler.getClass()),
                        new AnnotationMessageTypeResolver(),
                        new DelegatingMessageConverter(PassThroughConverter.INSTANCE)
                ));

                CompletableFuture<Integer> result = queryGateway.query(new EchoValue(echoInt), Integer.class, null);
                assertThat(result).isDone();
                assertThat(result.join()).isEqualTo(echoInt);
            }

            @Test
            void returningOptionalShouldUseResult() {
                int echoInt = 1;
                OptionalQueryHandler handler = new OptionalQueryHandler();
                queryBus.subscribe(new AnnotatedQueryHandlingComponent<>(
                        handler,
                        ClasspathParameterResolverFactory.forClass(handler.getClass()),
                        ClasspathHandlerDefinition.forClass(handler.getClass()),
                        new AnnotationMessageTypeResolver(),
                        new DelegatingMessageConverter(PassThroughConverter.INSTANCE)
                ));

                CompletableFuture<Integer> result = queryGateway.query(new EchoValue(echoInt), Integer.class, null);
                assertThat(result).isDone();
                assertThat(result.join()).isEqualTo(echoInt);
            }

            @Test
            void returningCompletableFutureShouldUseItsResult() {
                int echoInt = 1;
                CompletableFutureQueryHandler handler = new CompletableFutureQueryHandler();
                queryBus.subscribe(new AnnotatedQueryHandlingComponent<>(
                        handler,
                        ClasspathParameterResolverFactory.forClass(handler.getClass()),
                        ClasspathHandlerDefinition.forClass(handler.getClass()),
                        new AnnotationMessageTypeResolver(),
                        new DelegatingMessageConverter(PassThroughConverter.INSTANCE)
                ));

                CompletableFuture<Integer> result = queryGateway.query(new EchoValue(echoInt), Integer.class, null);
                assertThat(result).isDone();
                assertThat(result.join()).isEqualTo(echoInt);
            }

            @Test
            void returningMonoShouldUseItsResult() {
                int echoInt = 1;
                MonoQueryHandler handler = new MonoQueryHandler();
                queryBus.subscribe(new AnnotatedQueryHandlingComponent<>(
                        handler,
                        ClasspathParameterResolverFactory.forClass(handler.getClass()),
                        ClasspathHandlerDefinition.forClass(handler.getClass()),
                        new AnnotationMessageTypeResolver(),
                        new DelegatingMessageConverter(PassThroughConverter.INSTANCE)
                ));

                CompletableFuture<Integer> result = queryGateway.query(new EchoValue(echoInt), Integer.class, null);
                assertThat(result).isDone();
                assertThat(result.join()).isEqualTo(echoInt);
            }

            @Test
            void returningIterableShouldUseItsResult() {
                int echoIntOne = 1;
                int echoIntTwo = 2;
                IterableQueryHandler handler = new IterableQueryHandler();
                queryBus.subscribe(new AnnotatedQueryHandlingComponent<>(
                        handler,
                        ClasspathParameterResolverFactory.forClass(handler.getClass()),
                        ClasspathHandlerDefinition.forClass(handler.getClass()),
                        new AnnotationMessageTypeResolver(),
                        new DelegatingMessageConverter(PassThroughConverter.INSTANCE)
                ));

                CompletableFuture<List<Integer>> result =
                        queryGateway.queryMany(new EchoValue(echoIntOne, echoIntTwo), Integer.class, null);
                assertThat(result).isDone();
                assertThat(result.join()).contains(echoIntOne, echoIntTwo);
            }

            @Test
            void returningStreamShouldUseItsResult() {
                int echoIntOne = 1;
                int echoIntTwo = 2;
                StreamQueryHandler handler = new StreamQueryHandler();
                queryBus.subscribe(new AnnotatedQueryHandlingComponent<>(
                        handler,
                        ClasspathParameterResolverFactory.forClass(handler.getClass()),
                        ClasspathHandlerDefinition.forClass(handler.getClass()),
                        new AnnotationMessageTypeResolver(),
                        new DelegatingMessageConverter(PassThroughConverter.INSTANCE)
                ));

                CompletableFuture<List<Integer>> result =
                        queryGateway.queryMany(new EchoValue(echoIntOne, echoIntTwo), Integer.class, null);
                assertThat(result).isDone();
                assertThat(result.join()).contains(echoIntOne, echoIntTwo);
            }

            @Test
            void returningFluxShouldUseItsResult() {
                int echoIntOne = 1;
                int echoIntTwo = 2;
                FluxQueryHandler handler = new FluxQueryHandler();
                queryBus.subscribe(new AnnotatedQueryHandlingComponent<>(
                        handler,
                        ClasspathParameterResolverFactory.forClass(handler.getClass()),
                        ClasspathHandlerDefinition.forClass(handler.getClass()),
                        new AnnotationMessageTypeResolver(),
                        new DelegatingMessageConverter(PassThroughConverter.INSTANCE)
                ));

                CompletableFuture<List<Integer>> result =
                        queryGateway.queryMany(new EchoValue(echoIntOne, echoIntTwo), Integer.class, null);
                assertThat(result).isDone();
                assertThat(result.join()).contains(echoIntOne, echoIntTwo);
            }
        }
    }

    @Nested
    class Declarative {

        @Nested
        class EventHandlers {
            // TODO #3392 - Once EventSink is created, can adds declartive tests here which subscribe with a QualifiedName
        }

        @Nested
        class CommandHandlers {

            @Test
            void returningCompletableFutureShouldUseItsResult() {
                commandBus.subscribe(
                        new QualifiedName(CheckIfPrime.class),
                        (command, context) -> {
                            CommandResultMessage value = new GenericCommandResultMessage(
                                    null, isPrime(((CheckIfPrime) command.payload()).value())
                            );

                            return MessageStream.fromFuture(CompletableFuture.completedFuture(value));
                        }
                );

                assertCommands();
            }

            @Test
            void returningMonoShouldUseItsResult() {
                commandBus.subscribe(
                        new QualifiedName(CheckIfPrime.class),
                        (command, context) -> {
                            CommandResultMessage data = new GenericCommandResultMessage(
                                    null, isPrime(((CheckIfPrime) command.payload()).value())
                            );

                            return MonoUtils.asSingle(Mono.just(data));
                        }
                );

                assertCommands();
            }

            @Test
            void returningBooleanShouldUseResult() {
                commandBus.subscribe(
                        new QualifiedName(CheckIfPrime.class),
                        (command, context) -> MessageStream.just(new GenericCommandResultMessage(
                                null, isPrime(((CheckIfPrime) command.payload()).value())
                        ))
                );

                assertCommands();
            }
        }

        @Nested
        class QueryHandlers {

            @Test
            void declarativeQueryHandlerShouldUseFluxReturnType() throws Exception {
                queryBus.subscribe(
                        new QualifiedName(GetKnownPrimes.class),
                        (query, context) -> FluxUtils.asMessageStream(
                                Flux.just(2, 3, 5, 7)
                                    .map(i -> new GenericQueryResponseMessage(new MessageType(Integer.class), i))
                        )
                );

                assertQuery();
            }

            @Test
            void declarativeQueryHandlerShouldUseIterableReturnType() throws Exception {
                queryBus.subscribe(
                        new QualifiedName(GetKnownPrimes.class),
                        (query, context) -> MessageStream.fromIterable(List.of(
                                new GenericQueryResponseMessage(new MessageType(Integer.class), 2),
                                new GenericQueryResponseMessage(new MessageType(Integer.class), 3),
                                new GenericQueryResponseMessage(new MessageType(Integer.class), 5),
                                new GenericQueryResponseMessage(new MessageType(Integer.class), 7)
                        ))
                );

                assertQuery();
            }
        }
    }

    private void assertEvents() {
        eventBus.publish(null, new GenericEventMessage(new MessageType(PrimeChecked.class), new PrimeChecked(5)));

        Awaitility.await().untilAsserted(() -> assertThat(eventHandlerCalled).isTrue());
    }

    private void assertCommands() {
        assertThat(commandGateway.sendAndWait(new CheckIfPrime(2), Boolean.class)).isTrue();
        assertThat(commandGateway.sendAndWait(new CheckIfPrime(4), Boolean.class)).isFalse();
        assertThatThrownBy(() -> commandGateway.sendAndWait(new CheckIfPrime(10), Boolean.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("unsupported value: 10");
    }

    private void assertQuery() throws Exception {
        List<Integer> primes = queryGateway.queryMany(new GetKnownPrimes(), Integer.class, null)
                                           .get();

        assertThat(primes).isEqualTo(List.of(2, 3, 5, 7));
    }

    private class VoidEventHandler {

        @SuppressWarnings("unused")
        @EventHandler
        public void handle(PrimeChecked event) {
            eventHandlerCalled.set(true);
        }
    }

    private class MonoEventHandler {

        @SuppressWarnings("unused")
        @EventHandler
        public Mono<Void> handle(PrimeChecked event) {
            return Mono.fromRunnable(() -> eventHandlerCalled.set(true));
        }
    }

    private class CompletableFutureEventHandler {

        @SuppressWarnings("unused")
        @EventHandler
        public CompletableFuture<Void> handle(PrimeChecked event) {
            return CompletableFuture.runAsync(() -> eventHandlerCalled.set(true));
        }
    }

    private static class CompletableFutureCommandHandler {

        @SuppressWarnings("unused")
        @CommandHandler
        public Future<Boolean> handle(CheckIfPrime cmd) {
            return CompletableFuture.completedFuture(isPrime(cmd.value));
        }
    }

    private static class MonoCommandHandler {

        @SuppressWarnings("unused")
        @CommandHandler
        public Mono<Boolean> handle(CheckIfPrime cmd) {
            return Mono.just(isPrime(cmd.value));
        }
    }

    private static class BooleanCommandHandler {

        @SuppressWarnings("unused")
        @CommandHandler
        public boolean handle(CheckIfPrime cmd) {
            return isPrime(cmd.value);
        }
    }

    private static class JustQueryHandler {

        @SuppressWarnings("unused")
        @QueryHandler
        public Integer handle(EchoValue query) {
            return query.values()[0];
        }
    }

    private static class PrimitiveQueryHandler {

        @SuppressWarnings("unused")
        @QueryHandler
        public int handle(EchoValue query) {
            return query.values()[0];
        }
    }

    private static class OptionalQueryHandler {

        @SuppressWarnings("unused")
        @QueryHandler
        public Optional<Integer> handle(EchoValue query) {
            return Optional.of(query.values()[0]);
        }
    }

    private static class CompletableFutureQueryHandler {

        @SuppressWarnings("unused")
        @QueryHandler
        public CompletableFuture<Integer> handle(EchoValue query) {
            return CompletableFuture.completedFuture(query.values()[0]);
        }
    }

    private static class IterableQueryHandler {

        @SuppressWarnings("unused")
        @QueryHandler
        public Iterable<Integer> handle(EchoValue query) {
            return List.of(query.values()[0], query.values()[1]);
        }
    }

    private static class StreamQueryHandler {

        @SuppressWarnings("unused")
        @QueryHandler
        public Stream<Integer> handle(EchoValue query) {
            return Stream.of(query.values()[0], query.values()[1]);
        }
    }

    private static class MonoQueryHandler {

        @SuppressWarnings("unused")
        @QueryHandler
        public Mono<Integer> handle(EchoValue query) {
            return Mono.just(query.values()[0]);
        }
    }

    private static class FluxQueryHandler {

        @SuppressWarnings("unused")
        @QueryHandler
        public Flux<Integer> handle(EchoValue query) {
            return Flux.just(query.values()[0], query.values()[1]);
        }
    }

    private static boolean isPrime(int n) {
        return switch (n) {
            case 0, 1, 4 -> false;
            case 2, 3, 5 -> true;
            default -> throw new IllegalArgumentException("unsupported value: " + n);
        };
    }
}
