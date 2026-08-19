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

package org.axonframework.messaging.queryhandling.configuration;

import org.axonframework.common.infra.MockComponentDescriptor;
import org.axonframework.common.configuration.AxonConfiguration;
import org.axonframework.common.configuration.ComponentBuilder;
import org.axonframework.common.configuration.Configuration;
import org.axonframework.common.configuration.StubLifecycleRegistry;
import org.axonframework.messaging.core.configuration.MessagingConfigurer;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageHandlingExceptionHandler;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.correlation.CorrelationDataProviderRegistry;
import org.axonframework.messaging.core.correlation.DefaultCorrelationDataProviderRegistry;
import org.axonframework.messaging.core.unitofwork.StubProcessingContext;
import org.axonframework.messaging.queryhandling.GenericQueryMessage;
import org.axonframework.messaging.queryhandling.QueryHandlingComponent;
import org.axonframework.messaging.queryhandling.annotation.QueryHandler;
import org.axonframework.messaging.queryhandling.QueryBus;
import org.axonframework.messaging.queryhandling.interception.InterceptingQueryHandlingComponent;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class validating the {@link QueryHandlingModule}.
 *
 * @author Steven van Beelen
 */
class SimpleQueryHandlingModuleTest {

    private static final QualifiedName QUERY_NAME = new QualifiedName(String.class);

    private QueryHandlingModule.SetupPhase setupPhase;
    private QueryHandlingModule.QueryHandlerPhase queryHandlerPhase;

    @BeforeEach
    void setUp() {
        setupPhase = QueryHandlingModule.named("test-subject");
        queryHandlerPhase = setupPhase.queryHandlers();
    }

    @Test
    void nameReturnsModuleName() {
        assertEquals("test-subject", setupPhase.queryHandlers().build().name());
    }

    @Test
    void buildRegistersQueryHandlers() {
        // Registers default provider registry to remove MessageOriginProvider, thus removing CorrelationDataInterceptor.
        // This ensures we keep the SimpleQueryBus, from which we can retrieve the subscription for validation.
        AxonConfiguration configuration = MessagingConfigurer
                .create()
                .componentRegistry(cr -> cr.registerComponent(
                        CorrelationDataProviderRegistry.class, c -> new DefaultCorrelationDataProviderRegistry()
                ))
                .componentRegistry(cr -> cr.registerModule(
                        setupPhase.queryHandlers()
                                  .queryHandler(
                                          QUERY_NAME,
                                          (query, context) -> MessageStream.just(null)
                                  )
                                  .build()
                ))
                .start();

        Configuration resultConfig = configuration.getModuleConfiguration("test-subject").orElseThrow();

        MockComponentDescriptor descriptor = new MockComponentDescriptor();
        resultConfig.getComponent(QueryBus.class).describeTo(descriptor);

        Map<QualifiedName, QueryHandlingComponent> subscriptions = descriptor.getProperty("subscriptions");
        assertTrue(subscriptions.containsKey(QUERY_NAME));
    }

    @Test
    void buildAnnotatedQueryHandlingComponentSucceedsAndRegisters() {
        //noinspection unused
        var myQueryHandlingObject = new Object() {
            @QueryHandler
            public String handle(String query) {
                return query;
            }
        };

        Configuration resultConfig =
                setupPhase.queryHandlers()
                          .autodetectedQueryHandlingComponent(c -> myQueryHandlingObject)
                          .build()
                          .build(MessagingConfigurer.create().build(), new StubLifecycleRegistry());

        Optional<QueryHandlingComponent> optionalHandlingComponent = resultConfig.getOptionalComponent(
                QueryHandlingComponent.class, "QueryHandlingComponent[test-subject]");
        assertTrue(optionalHandlingComponent.isPresent());
        assertTrue(optionalHandlingComponent.get().supportedQueries()
                                            .contains(QUERY_NAME));
    }

    @Test
    void buildMessagingConfigurationSucceedsAndRegistersTheModuleWithComponent() {
        //noinspection unused
        var myQueryHandlingObject = new Object() {
            @QueryHandler()
            public String handle(String query) {
                return query;
            }
        };

        Configuration resultConfig =
                MessagingConfigurer.create()
                                   .registerQueryHandlingModule(
                                           setupPhase.queryHandlers()
                                                     .autodetectedQueryHandlingComponent(c -> myQueryHandlingObject)
                                                     .build()
                                   ).build();


        Optional<QueryHandlingComponent> optionalHandlingComponent = resultConfig
                .getModuleConfiguration("test-subject")
                .flatMap(m -> m.getOptionalComponent(
                        QueryHandlingComponent.class, "QueryHandlingComponent[test-subject]"
                ));
        assertTrue(optionalHandlingComponent.isPresent());
        assertTrue(optionalHandlingComponent.get().supportedQueries().contains(QUERY_NAME));
    }

    @Test
    void namedThrowsNullPointerExceptionForNullModuleName() {
        //noinspection DataFlowIssue
        assertThrows(NullPointerException.class, () -> QueryHandlingModule.named(null));
    }

    @Test
    void namedThrowsIllegalArgumentExceptionForEmptyModuleName() {
        assertThrows(IllegalArgumentException.class, () -> QueryHandlingModule.named(""));
    }

    @Test
    void queryHandlerThrowsNullPointerExceptionForNullQueryName() {
        //noinspection DataFlowIssue
        assertThrows(
                NullPointerException.class,
                () -> queryHandlerPhase.queryHandler(null, (query, context) -> MessageStream.just(null))
        );
    }

    @Test
    void queryHandlerThrowsNullPointerExceptionForNullQueryHandler() {
        //noinspection DataFlowIssue
        assertThrows(NullPointerException.class,
                     () -> queryHandlerPhase.queryHandler(QUERY_NAME, (org.axonframework.messaging.queryhandling.QueryHandler) null));
    }

    @Test
    void queryHandlerThrowsNullPointerExceptionForNullQueryNameWithQueryHandler() {
        //noinspection DataFlowIssue
        assertThrows(
                NullPointerException.class,
                () -> queryHandlerPhase.queryHandler(null, (query, context) -> MessageStream.just(null))
        );
    }

    @Test
    void queryHandlerThrowsNullPointerExceptionForNullQueryNameWithQueryHandlerBuilder() {
        //noinspection DataFlowIssue
        assertThrows(NullPointerException.class, () -> queryHandlerPhase.queryHandler(
                null, c -> (query, context) -> null
        ));
    }

    @Test
    void queryHandlerThrowsNullPointerExceptionForNullQueryHandlerBuilder() {
        //noinspection DataFlowIssue
        assertThrows(NullPointerException.class, () -> queryHandlerPhase.queryHandler(
                QUERY_NAME, (ComponentBuilder<org.axonframework.messaging.queryhandling.QueryHandler>) null
        ));
    }

    @Test
    void queryHandlingComponentThrowsNullPointerExceptionForNullQueryHandlingComponentBuilder() {
        //noinspection DataFlowIssue
        assertThrows(NullPointerException.class, () -> queryHandlerPhase.queryHandlingComponent(null));
    }

    @Test
    void annotatedQueryHandlingComponentThrowsNullPointerExceptionForNullQueryHandlingComponentBuilder() {
        //noinspection DataFlowIssue
        assertThrows(NullPointerException.class, () -> queryHandlerPhase.autodetectedQueryHandlingComponent(null));
    }

    @Test
    void commandHandlingThrowsNullPointerExceptionForNullQueryHandlerPhaseConsumer() {
        //noinspection DataFlowIssue
        assertThrows(NullPointerException.class, () -> setupPhase.queryHandlers(null));
    }

    @Nested
    class InterceptedTest {

        private static final GenericQueryMessage SAMPLE_QUERY =
                new GenericQueryMessage(new MessageType(String.class), "payload");

        @Test
        void singleInterceptorIsInvokedBeforeHandler() {
            // given
            List<String> invocationLog = new ArrayList<>();
            var component = buildModule(queryHandlerPhase
                    .queryHandler(QUERY_NAME, (query, ctx) -> {
                        invocationLog.add("handler");
                        return MessageStream.empty().cast();
                    })
                    .intercepted(cfg -> (msg, ctx, chain) -> {
                        invocationLog.add("interceptor");
                        return chain.proceed(msg, ctx);
                    }));

            // when
            component.handle(SAMPLE_QUERY, StubProcessingContext.forMessage(SAMPLE_QUERY))
                     .first().asCompletableFuture()
                     .orTimeout(5, TimeUnit.SECONDS).join();

            // then
            assertThat(invocationLog).containsExactly("interceptor", "handler");
        }

        @Test
        void multipleInterceptorsAreInvokedInRegistrationOrder() {
            // given
            List<String> invocationLog = new ArrayList<>();
            var component = buildModule(queryHandlerPhase
                    .queryHandler(QUERY_NAME, (query, ctx) -> {
                        invocationLog.add("handler");
                        return MessageStream.empty().cast();
                    })
                    .intercepted(cfg -> (msg, ctx, chain) -> {
                        invocationLog.add("first");
                        return chain.proceed(msg, ctx);
                    })
                    .intercepted(cfg -> (msg, ctx, chain) -> {
                        invocationLog.add("second");
                        return chain.proceed(msg, ctx);
                    }));

            // when
            component.handle(SAMPLE_QUERY, StubProcessingContext.forMessage(SAMPLE_QUERY))
                     .first().asCompletableFuture()
                     .orTimeout(5, TimeUnit.SECONDS).join();

            // then
            assertThat(invocationLog).containsExactly("first", "second", "handler");
        }

        @Test
        void noWrappingWhenNoInterceptorsRegistered() {
            // given / when
            var component = buildModule(queryHandlerPhase
                    .queryHandler(QUERY_NAME, (query, ctx) -> MessageStream.empty().cast()));

            // then
            assertThat(component).isNotInstanceOf(InterceptingQueryHandlingComponent.class);
        }

        @Test
        void wrappingOccursWhenInterceptorIsRegistered() {
            // given / when
            var component = buildModule(queryHandlerPhase
                    .queryHandler(QUERY_NAME, (query, ctx) -> MessageStream.empty().cast())
                    .intercepted(cfg -> (msg, ctx, chain) -> chain.proceed(msg, ctx)));

            // then
            assertThat(component).isInstanceOf(InterceptingQueryHandlingComponent.class);
        }

        @Test
        void interceptorCanShortCircuitHandling() {
            // given
            List<String> invocationLog = new ArrayList<>();
            var component = buildModule(queryHandlerPhase
                    .queryHandler(QUERY_NAME, (query, ctx) -> {
                        invocationLog.add("handler");
                        return MessageStream.empty().cast();
                    })
                    .intercepted(cfg -> (msg, ctx, chain) -> MessageStream.failed(new RuntimeException("access denied"))));

            // when / then
            assertThat(component.handle(SAMPLE_QUERY, StubProcessingContext.forMessage(SAMPLE_QUERY))
                                .first().asCompletableFuture())
                    .failsWithin(5, TimeUnit.SECONDS)
                    .withThrowableOfType(ExecutionException.class)
                    .withCauseInstanceOf(RuntimeException.class)
                    .withMessageContaining("access denied");

            assertThat(invocationLog).isEmpty();
        }

        @SuppressWarnings("DataFlowIssue")
        @Test
        void interceptorNullThrowsNullPointerException() {
            // given / when / then
            assertThrows(NullPointerException.class, () -> queryHandlerPhase.intercepted(null));
        }

        private QueryHandlingComponent buildModule(QueryHandlingModule.QueryHandlerPhase phase) {
            var config = phase.build()
                              .build(MessagingConfigurer.create().build(), new StubLifecycleRegistry());
            return config.getComponent(QueryHandlingComponent.class, "QueryHandlingComponent[test-subject]");
        }
    }

    @Nested
    class WithExceptionHandlerTest {

        private static final QualifiedName QUERY_NAME_LOCAL = new QualifiedName("test-query");
        private static final GenericQueryMessage SAMPLE_QUERY =
                new GenericQueryMessage(new MessageType("test-query"), "payload");
        private static final StubProcessingContext STUB_PROCESSING_CONTEXT = new StubProcessingContext();

        private static QueryHandlingComponent buildComponent(QueryHandlingModule.QueryHandlerPhase phase) {
            var moduleName = "test-ex";
            Configuration config = phase.build()
                                        .build(MessagingConfigurer.create().build(), new StubLifecycleRegistry());
            return config.getOptionalComponent(QueryHandlingComponent.class,
                                               "QueryHandlingComponent[" + moduleName + "]")
                         .orElseThrow();
        }

        @Test
        void exceptionHandlerIsInvokedWhenHandlerThrows() {
            // given
            List<String> invocationLog = new ArrayList<>();
            var component = buildComponent(
                    QueryHandlingModule.named("test-ex")
                                       .queryHandlers()
                                       .queryHandler(QUERY_NAME_LOCAL,
                                                     (q, ctx) -> MessageStream.failed(new RuntimeException("handler failed")))
                                       .withExceptionHandler(c -> (q, ctx, error) -> {
                                           invocationLog.add("exceptionHandler");
                                           return MessageStream.empty();
                                       })
            );

            // when
            var result = component.handle(SAMPLE_QUERY, STUB_PROCESSING_CONTEXT);
            result.peek(); // force lazy evaluation of the onErrorContinue chain

            // then
            assertThat(invocationLog).containsExactly("exceptionHandler");
        }

        @Test
        void exceptionHandlerCanSuppressException() {
            // given
            var component = buildComponent(
                    QueryHandlingModule.named("test-ex")
                                       .queryHandlers()
                                       .queryHandler(QUERY_NAME_LOCAL,
                                                     (q, ctx) -> MessageStream.failed(new RuntimeException("handler failed")))
                                       .withExceptionHandler(c -> (q, ctx, error) -> MessageStream.empty())
            );

            // when - exception handler returns empty, suppressing the error
            var result = component.handle(SAMPLE_QUERY, STUB_PROCESSING_CONTEXT);
            result.peek(); // force lazy evaluation of the onErrorContinue chain

            // then
            assertThat(result.error()).isEmpty();
        }

        @Test
        void exceptionHandlerCanPropagateError() {
            // given
            var component = buildComponent(
                    QueryHandlingModule.named("test-ex")
                                       .queryHandlers()
                                       .queryHandler(QUERY_NAME_LOCAL,
                                                     (q, ctx) -> MessageStream.failed(new RuntimeException("original")))
                                       .withExceptionHandler(c -> (q, ctx, error) -> MessageStream.failed(new IOException("wrapped")))
            );

            // when - exception handler returns a failed stream, the error propagates
            var result = component.handle(SAMPLE_QUERY, STUB_PROCESSING_CONTEXT);
            result.peek(); // force lazy evaluation of the onErrorContinue chain

            // then
            assertThat(result.error()).isPresent();
            assertThat(result.error().get()).isInstanceOf(IOException.class).hasMessage("wrapped");
        }

        @Test
        void exceptionHandlerUnexpectedThrowIsWrappedInFailedStream() {
            // given
            var component = buildComponent(
                    QueryHandlingModule.named("test-ex")
                                       .queryHandlers()
                                       .queryHandler(QUERY_NAME_LOCAL,
                                                     (q, ctx) -> MessageStream.failed(new RuntimeException("original")))
                                       .withExceptionHandler(c -> (q, ctx, error) -> {
                                           throw new RuntimeException("unexpected");
                                       })
            );

            // when - exception handler throws unexpectedly, the thrown exception propagates as a failed stream
            var result = component.handle(SAMPLE_QUERY, STUB_PROCESSING_CONTEXT);
            result.peek(); // force lazy evaluation of the onErrorContinue chain

            // then
            assertThat(result.error()).isPresent();
            assertThat(result.error().get()).isInstanceOf(RuntimeException.class).hasMessage("unexpected");
        }

        @Test
        void genericMessageExceptionHandlerCanBeRegistered() {
            // given - a generic handler typed at Message that could be shared across all message types
            List<String> invocationLog = new ArrayList<>();
            MessageHandlingExceptionHandler<Message> genericHandler = (msg, ctx, error) -> {
                invocationLog.add("generic:" + msg.type().name() + ":" + error.getMessage());
                return MessageStream.empty();
            };
            var component = buildComponent(
                    QueryHandlingModule.named("test-ex")
                                       .queryHandlers()
                                       .queryHandler(QUERY_NAME_LOCAL,
                                                     (q, ctx) -> MessageStream.failed(new RuntimeException("boom")))
                                       .withExceptionHandler(c -> genericHandler)
            );

            // when
            var result = component.handle(SAMPLE_QUERY, STUB_PROCESSING_CONTEXT);
            result.peek();

            // then
            assertThat(invocationLog).containsExactly("generic:test-query:boom");
            assertThat(result.error()).isEmpty();
        }

        @Test
        void lastRegisteredHandlerSeesExceptionFirst() {
            // given
            List<String> invocationLog = new ArrayList<>();
            var component = buildComponent(
                    QueryHandlingModule.named("test-ex")
                                       .queryHandlers()
                                       .queryHandler(QUERY_NAME_LOCAL,
                                                     (q, ctx) -> MessageStream.failed(new RuntimeException("handler failed")))
                                       .withExceptionHandler(c -> (q, ctx, error) -> {
                                           // first registered: outermost interceptor, runs after second propagates
                                           invocationLog.add("first");
                                           return MessageStream.empty();
                                       })
                                       .withExceptionHandler(c -> (q, ctx, error) -> {
                                           // second registered: innermost interceptor (closest to handler), sees exception first
                                           invocationLog.add("second");
                                           return MessageStream.failed(error);
                                       })
            );

            // when
            var result = component.handle(SAMPLE_QUERY, STUB_PROCESSING_CONTEXT);
            result.peek(); // force lazy evaluation of the onErrorContinue chain

            // then - last registered handler (innermost) runs first
            assertThat(invocationLog).containsExactly("second", "first");
        }
    }
}
