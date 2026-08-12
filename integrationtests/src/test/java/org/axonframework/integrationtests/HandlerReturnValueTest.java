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

package org.axonframework.integrationtests;

import org.axonframework.common.configuration.AxonConfiguration;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.commandhandling.configuration.CommandHandlingModule;
import org.axonframework.messaging.commandhandling.gateway.CommandGateway;
import org.axonframework.messaging.core.configuration.MessagingConfigurer;
import org.axonframework.messaging.queryhandling.annotation.QueryHandler;
import org.axonframework.messaging.queryhandling.configuration.QueryHandlingModule;
import org.axonframework.messaging.queryhandling.gateway.QueryGateway;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test validating how many responses a message handler's return value becomes, and thus what the dispatcher
 * receives back.
 * <p>
 * Command handlers produce exactly one result, so a returned collection reaches the dispatcher as a whole. Query
 * handlers may produce several, so a returned collection reaches the dispatcher as one response per element. Both hold
 * regardless of the {@link CompletableFuture} and {@link Optional} wrappers the return value is nested in, and single
 * values are unaffected by either rule.
 *
 * @author Mitchell Herrijgers
 */
class HandlerReturnValueTest {

    private static final List<String> ELEMENTS = List.of("first", "second", "third");
    private static final String SINGLE = "only";

    private AxonConfiguration configuration;
    private CommandGateway commandGateway;
    private QueryGateway queryGateway;

    @BeforeEach
    void setUp() {
        configuration = MessagingConfigurer.create()
                                           .registerCommandHandlingModule(
                                                   CommandHandlingModule.named("return-value-commands")
                                                                        .commandHandlers()
                                                                        .autodetectedCommandHandlingComponent(
                                                                                c -> new ReturnValueCommandHandler())
                                           )
                                           .registerQueryHandlingModule(
                                                   QueryHandlingModule.named("return-value-queries")
                                                                      .queryHandlers()
                                                                      .autodetectedQueryHandlingComponent(
                                                                              c -> new ReturnValueQueryHandler())
                                           )
                                           .start();
        commandGateway = configuration.getComponent(CommandGateway.class);
        queryGateway = configuration.getComponent(QueryGateway.class);
    }

    @AfterEach
    void tearDown() {
        configuration.shutdown();
    }

    @Nested
    class CommandHandlerReturningACollection {

        @Test
        void sendAndWaitReturnsTheCompleteList() {
            // when
            List<?> result = commandGateway.sendAndWait(new ListCommand("id"), List.class);

            // then
            assertThat(result).isEqualTo(ELEMENTS);
        }

        @Test
        void sendAndWaitReturnsTheCompleteListForAnAsyncHandler() {
            // when
            List<?> result = commandGateway.sendAndWait(new AsyncListCommand("id"), List.class);

            // then
            assertThat(result).isEqualTo(ELEMENTS);
        }
    }

    @Nested
    class CommandHandlerReturningASingleValue {

        @Test
        void sendAndWaitReturnsThePlainValue() {
            // when
            String result = commandGateway.sendAndWait(new SingleCommand("id"), String.class);

            // then
            assertThat(result).isEqualTo(SINGLE);
        }

        @Test
        void sendAndWaitUnwrapsAnOptionalValue() {
            // when
            String result = commandGateway.sendAndWait(new OptionalCommand("id"), String.class);

            // then
            assertThat(result).isEqualTo(SINGLE);
        }

        @Test
        void sendAndWaitReturnsTheValueOfAnAsyncHandler() {
            // when
            String result = commandGateway.sendAndWait(new AsyncSingleCommand("id"), String.class);

            // then
            assertThat(result).isEqualTo(SINGLE);
        }

        @Test
        void sendAndWaitUnwrapsAnOptionalValueOfAnAsyncHandler() {
            // when
            String result = commandGateway.sendAndWait(new AsyncOptionalCommand("id"), String.class);

            // then
            assertThat(result).isEqualTo(SINGLE);
        }

        @Test
        void sendAndWaitReturnsNullWhenTheHandlerProducesNoResult() {
            // when / then
            assertThat(commandGateway.sendAndWait(new NullCommand("id"), String.class)).isNull();
            assertThat(commandGateway.sendAndWait(new EmptyOptionalCommand("id"), String.class)).isNull();
        }
    }

    @Nested
    class QueryHandlerReturningACollection {

        @Test
        void queryManyReturnsAllElements() throws Exception {
            // when
            List<String> result = queryGateway.queryMany(new ListQuery("id"), String.class, null)
                                              .get(5, TimeUnit.SECONDS);

            // then
            assertThat(result).isEqualTo(ELEMENTS);
        }

        @Test
        void queryManyReturnsAllElementsForAnAsyncHandler() throws Exception {
            // when
            List<String> result = queryGateway.queryMany(new AsyncListQuery("id"), String.class, null)
                                              .get(5, TimeUnit.SECONDS);

            // then
            assertThat(result).isEqualTo(ELEMENTS);
        }

        @Test
        void queryReturnsTheFirstResponseOfAMultiResponseHandler() throws Exception {
            // given a handler returning a collection, which declares it produces several responses
            // when asking for a single response
            String result = queryGateway.query(new ListQuery("id"), String.class, null)
                                        .get(5, TimeUnit.SECONDS);

            // then the first response is returned, the remainder is discarded
            assertThat(result).isEqualTo(ELEMENTS.get(0));
        }
    }

    @Nested
    class QueryHandlerReturningASingleValue {

        @Test
        void queryReturnsThePlainValue() throws Exception {
            // when
            String result = queryGateway.query(new SingleQuery("id"), String.class, null)
                                        .get(5, TimeUnit.SECONDS);

            // then
            assertThat(result).isEqualTo(SINGLE);
        }

        @Test
        void queryUnwrapsAnOptionalValue() throws Exception {
            // when
            String result = queryGateway.query(new OptionalQuery("id"), String.class, null)
                                        .get(5, TimeUnit.SECONDS);

            // then
            assertThat(result).isEqualTo(SINGLE);
        }

        @Test
        void queryReturnsTheValueOfAnAsyncHandler() throws Exception {
            // when
            String result = queryGateway.query(new AsyncSingleQuery("id"), String.class, null)
                                        .get(5, TimeUnit.SECONDS);

            // then
            assertThat(result).isEqualTo(SINGLE);
        }

        @Test
        void queryManyReturnsExactlyOneElement() throws Exception {
            // when asking for several responses from a handler producing one
            List<String> result = queryGateway.queryMany(new SingleQuery("id"), String.class, null)
                                              .get(5, TimeUnit.SECONDS);

            // then that one response is the only element
            assertThat(result).containsExactly(SINGLE);
        }

        @Test
        void queryReturnsNullWhenTheHandlerProducesNoResult() throws Exception {
            // when / then
            assertThat(queryGateway.query(new NullQuery("id"), String.class, null).get(5, TimeUnit.SECONDS)).isNull();
            assertThat(queryGateway.query(new EmptyOptionalQuery("id"), String.class, null)
                                   .get(5, TimeUnit.SECONDS)).isNull();
        }
    }

    record ListCommand(String id) {

    }

    record AsyncListCommand(String id) {

    }

    record SingleCommand(String id) {

    }

    record AsyncSingleCommand(String id) {

    }

    record OptionalCommand(String id) {

    }

    record AsyncOptionalCommand(String id) {

    }

    record NullCommand(String id) {

    }

    record EmptyOptionalCommand(String id) {

    }

    record ListQuery(String id) {

    }

    record AsyncListQuery(String id) {

    }

    record SingleQuery(String id) {

    }

    record AsyncSingleQuery(String id) {

    }

    record OptionalQuery(String id) {

    }

    record NullQuery(String id) {

    }

    record EmptyOptionalQuery(String id) {

    }

    static class ReturnValueCommandHandler {

        @CommandHandler
        List<String> handle(ListCommand command) {
            return ELEMENTS;
        }

        @CommandHandler
        CompletableFuture<List<String>> handle(AsyncListCommand command) {
            return CompletableFuture.completedFuture(ELEMENTS);
        }

        @CommandHandler
        String handle(SingleCommand command) {
            return SINGLE;
        }

        @CommandHandler
        CompletableFuture<String> handle(AsyncSingleCommand command) {
            return CompletableFuture.completedFuture(SINGLE);
        }

        @CommandHandler
        Optional<String> handle(OptionalCommand command) {
            return Optional.of(SINGLE);
        }

        @CommandHandler
        CompletableFuture<Optional<String>> handle(AsyncOptionalCommand command) {
            return CompletableFuture.completedFuture(Optional.of(SINGLE));
        }

        @CommandHandler
        @Nullable
        String handle(NullCommand command) {
            return null;
        }

        @CommandHandler
        Optional<String> handle(EmptyOptionalCommand command) {
            return Optional.empty();
        }
    }

    static class ReturnValueQueryHandler {

        @QueryHandler
        List<String> handle(ListQuery query) {
            return ELEMENTS;
        }

        @QueryHandler
        CompletableFuture<List<String>> handle(AsyncListQuery query) {
            return CompletableFuture.completedFuture(ELEMENTS);
        }

        @QueryHandler
        String handle(SingleQuery query) {
            return SINGLE;
        }

        @QueryHandler
        CompletableFuture<String> handle(AsyncSingleQuery query) {
            return CompletableFuture.completedFuture(SINGLE);
        }

        @QueryHandler
        Optional<String> handle(OptionalQuery query) {
            return Optional.of(SINGLE);
        }

        @QueryHandler
        @Nullable
        String handle(NullQuery query) {
            return null;
        }

        @QueryHandler
        Optional<String> handle(EmptyOptionalQuery query) {
            return Optional.empty();
        }
    }
}
