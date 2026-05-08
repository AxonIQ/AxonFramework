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
package org.axonframework.messaging.queryhandling.annotation;

import org.axonframework.common.util.MockException;
import org.axonframework.conversion.PassThroughConverter;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.MessageStream.Entry;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.MessageTypeResolver;
import org.axonframework.messaging.core.Metadata;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.annotation.AnnotationMessageTypeResolver;
import org.axonframework.messaging.core.annotation.ClasspathHandlerDefinition;
import org.axonframework.messaging.core.annotation.ClasspathParameterResolverFactory;
import org.axonframework.messaging.core.annotation.UnsupportedHandlerException;
import org.axonframework.messaging.core.conversion.DelegatingMessageConverter;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.StubProcessingContext;
import org.axonframework.messaging.queryhandling.GenericQueryMessage;
import org.axonframework.messaging.queryhandling.NoHandlerForQueryException;
import org.axonframework.messaging.queryhandling.QueryExecutionException;
import org.axonframework.messaging.queryhandling.QueryMessage;
import org.axonframework.messaging.queryhandling.QueryResponseMessage;
import org.junit.jupiter.api.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * Test class validating the {@link AnnotatedQueryHandlingComponent}.
 *
 * @author Marc Gathier
 * @author Steven van Beelen
 */
class AnnotatedQueryHandlingComponentTest {

    private final AtomicInteger callCount = new AtomicInteger();

    private AnnotatedQueryHandlingComponent<?> testSubject;

    @BeforeEach
    void setUp() {
        MyQueryHandler handler = new MyQueryHandler();
        testSubject = new AnnotatedQueryHandlingComponent<>(
                handler,
                ClasspathParameterResolverFactory.forClass(handler.getClass()),
                ClasspathHandlerDefinition.forClass(handler.getClass()),
                new AnnotationMessageTypeResolver(),
                new DelegatingMessageConverter(PassThroughConverter.INSTANCE)
        );
    }

    @Test
    void subscribesQueryHandlerWithQueryName() {
        Object annotatedQueryHandler = new Object() {
            @SuppressWarnings("unused")
            @QueryHandler(queryName = "myQueryName")
            public String handle(String query) {
                return "something";
            }
        };
        MessageTypeResolver messageTypeResolver = spy(new AnnotationMessageTypeResolver());
        AnnotatedQueryHandlingComponent<Object> annotatedComponent = new AnnotatedQueryHandlingComponent<>(
                annotatedQueryHandler,
                ClasspathParameterResolverFactory.forClass(annotatedQueryHandler.getClass()),
                ClasspathHandlerDefinition.forClass(annotatedQueryHandler.getClass()),
                messageTypeResolver,
                new DelegatingMessageConverter(PassThroughConverter.INSTANCE)
        );

        Set<QualifiedName> supportedQueries = annotatedComponent.supportedQueries();
        assertThat(supportedQueries).hasSize(1);
        assertThat(supportedQueries).contains(new QualifiedName("myQueryName"));
        verifyNoInteractions(messageTypeResolver);
    }

    @Test
    void subscribesQueryHandlerThroughMessageTypeResolverWhenQueryNameIsEmpty() {
        QualifiedName expectedName = new QualifiedName("defaultName");

        Object annotatedQueryHandler = new Object() {
            @SuppressWarnings({"unused", "DefaultAnnotationParam"})
            @QueryHandler(queryName = "") // Deliberately empty to give control to the MessageTypeResolver
            public String handle(String query) {
                return "something";
            }
        };
        AnnotatedQueryHandlingComponent<Object> annotatedComponent = new AnnotatedQueryHandlingComponent<>(
                annotatedQueryHandler,
                ClasspathParameterResolverFactory.forClass(annotatedQueryHandler.getClass()),
                ClasspathHandlerDefinition.forClass(annotatedQueryHandler.getClass()),
                payloadType -> Optional.of(new MessageType(expectedName)),
                new DelegatingMessageConverter(PassThroughConverter.INSTANCE)
        );

        Set<QualifiedName> supportedQueries = annotatedComponent.supportedQueries();
        assertThat(supportedQueries).hasSize(1);
        assertThat(supportedQueries).contains(expectedName);
    }

    @Test
    void subscribeFailsForHandlerWithInvalidParameters() {
        FaultyParameterOrderQueryHandler handler = new FaultyParameterOrderQueryHandler();
        assertThatThrownBy(() -> new AnnotatedQueryHandlingComponent<>(
                handler,
                ClasspathParameterResolverFactory.forClass(handler.getClass()),
                ClasspathHandlerDefinition.forClass(handler.getClass()),
                new AnnotationMessageTypeResolver(),
                new DelegatingMessageConverter(PassThroughConverter.INSTANCE)
        )).isInstanceOf(UnsupportedHandlerException.class);
    }

    @Test
    void subscribeFailsForHandlerWithVoidReturnType() {
        VoidReturnTypeQueryHandler handler = new VoidReturnTypeQueryHandler();
        assertThatThrownBy(() -> new AnnotatedQueryHandlingComponent<>(
                handler,
                ClasspathParameterResolverFactory.forClass(handler.getClass()),
                ClasspathHandlerDefinition.forClass(handler.getClass()),
                new AnnotationMessageTypeResolver(),
                new DelegatingMessageConverter(PassThroughConverter.INSTANCE)
        )).isInstanceOf(UnsupportedHandlerException.class);
    }

    @Test
    void handleReturnsFaultyMessageStreamWithNoHandlerForQueryException() {
        // given...
        QueryMessage testQuery =
                new GenericQueryMessage(new MessageType("unknown"), null);
        ProcessingContext testContext = StubProcessingContext.forMessage(testQuery);
        // when...
        MessageStream<QueryResponseMessage> result = testSubject.handle(testQuery, testContext);
        // then...
        assertThat(result.hasNextAvailable()).isFalse();
        Optional<Throwable> resultError = result.error();
        assertThat(resultError).isPresent();
        assertThat(resultError.get()).isInstanceOf(NoHandlerForQueryException.class);
    }

    @Test
    void handleInvokesQueryHandlerOfConcreteTypeReturningMessageStreamWithSingleResponse() {
        // given...
        String expectedResponse = "hello";
        QueryMessage testQuery =
                new GenericQueryMessage(new MessageType("echo"), expectedResponse);
        ProcessingContext testContext = StubProcessingContext.forMessage(testQuery);
        // when...
        MessageStream<QueryResponseMessage> result = testSubject.handle(testQuery, testContext);
        // then..
        assertThat(result.hasNextAvailable()).isTrue();
        Object responsePayload = result.first()
                                       .asCompletableFuture()
                                       .thenApply(MessageStream.Entry::message)
                                       .thenApply(Message::payload)
                                       .join();
        assertThat(expectedResponse).isEqualTo(responsePayload);
    }

    @Test
    void handleInvokesQueryHandlerReturningFaultyMessageStream() {
        // given...
        QueryMessage testQuery =
                new GenericQueryMessage(new MessageType("faulty"), "hello");
        ProcessingContext testContext = StubProcessingContext.forMessage(testQuery);
        // when...
        MessageStream<QueryResponseMessage> result = testSubject.handle(testQuery, testContext);
        // then...
        assertThat(result.hasNextAvailable()).isFalse();
        Optional<Throwable> resultError = result.error();
        assertThat(resultError).isPresent();
        assertThat(resultError.get()).isInstanceOf(QueryExecutionException.class);
    }

    @Test
    void handleInvokesQueryHandlerReturningAnEmptyOptional() {
        // given...
        QueryMessage testQuery = new GenericQueryMessage(new MessageType("emptyOptional"),
                                                         "hello");
        ProcessingContext testContext = StubProcessingContext.forMessage(testQuery);
        // when...
        MessageStream<QueryResponseMessage> result = testSubject.handle(testQuery, testContext);
        // then...
        assertThat(result.hasNextAvailable()).isFalse();
    }

    @Test
    void handleInvokesQueryHandlerReturningAnOptional() {
        // given...
        String expectedResponse = "hello";
        QueryMessage testQuery =
                new GenericQueryMessage(new MessageType("optional"), expectedResponse);
        ProcessingContext testContext = StubProcessingContext.forMessage(testQuery);
        // when...
        MessageStream<QueryResponseMessage> result = testSubject.handle(testQuery, testContext);
        // then...
        assertThat(result.hasNextAvailable()).isTrue();
        Object responsePayload = result.first()
                                       .asCompletableFuture()
                                       .thenApply(MessageStream.Entry::message)
                                       .thenApply(Message::payload)
                                       .join();
        assertThat(expectedResponse).isEqualTo(responsePayload);
    }

    @Test
    void handleInvokesQueryHandlerReturningCollectionResultingInMessageStreamWithMultipleResponses() {
        // given...
        int desiredResponsesCount = 5;
        QueryMessage testQuery = new GenericQueryMessage(
                new MessageType("multipleResponses"), desiredResponsesCount
        );
        ProcessingContext testContext = StubProcessingContext.forMessage(testQuery);
        // when...
        List<Object> results = testSubject.handle(testQuery, testContext)
                                          .reduce(new ArrayList<>(), (list, entry) -> {
                                              list.add(entry.message().payload());
                                              return list;
                                          })
                                          .join();
        // then...
        assertThat(results.size()).isEqualTo(desiredResponsesCount);
    }

    @Test
    void handleInvokesQueryHandlerReturningNull() {
        // given...
        QueryMessage testQuery = new GenericQueryMessage(new MessageType("nullResponse"),
                                                         "hello");
        ProcessingContext testContext = StubProcessingContext.forMessage(testQuery);
        // when...
        MessageStream<QueryResponseMessage> result = testSubject.handle(testQuery, testContext);
        // then...
        assertThat(result.hasNextAvailable()).isFalse();
    }

    @Nested
    class GivenAnAnnotatedInterfaceMethod {

        interface I {

            @QueryHandler(queryName = "call-counter")
            int handle(Integer event);
        }

        @Nested
        class WhenImplementedByAnnotedInstanceMethod {

            class T implements I {

                @Override
                @QueryHandler(queryName = "call-counter")
                public int handle(Integer event) {
                    return callCount.incrementAndGet();
                }
            }

            @Test
            void shouldCallHandlerOnlyOnce() {
                assertCalledOnlyOnce(new T());
            }

            @Nested
            class AndOverriddenAndAnnotatedInASubclass {

                class U extends T {

                    @Override
                    @QueryHandler(queryName = "call-counter")
                    public int handle(Integer event) {
                        return callCount.incrementAndGet();
                    }
                }

                @Test
                void shouldCallHandlerOnlyOnce() {
                    assertCalledOnlyOnce(new U());
                }
            }

            @Nested
            class AndOverriddenButNotAnnotatedInASubclass {

                class U extends T {

                    @Override
                    public int handle(Integer event) {
                        return callCount.incrementAndGet();
                    }
                }

                @Test
                void shouldCallHandlerOnlyOnce() {
                    assertCalledOnlyOnce(new U());
                }
            }
        }

        @Nested
        class WhenImplementedByUnannotedInstanceMethod {

            class T implements I {

                @Override
                public int handle(Integer event) {
                    return callCount.incrementAndGet();
                }
            }

            @Test
            void shouldCallHandlerOnlyOnce() {
                assertCalledOnlyOnce(new T());
            }

            @Nested
            class AndOverriddenAndAnnotatedInASubclass {

                class U extends T {

                    @Override
                    @QueryHandler(queryName = "call-counter")
                    public int handle(Integer event) {
                        return callCount.incrementAndGet();
                    }
                }

                @Test
                void shouldCallHandlerOnlyOnce() {
                    assertCalledOnlyOnce(new U());
                }
            }

            @Nested
            class AndOverriddenButNotAnnotatedInASubclass {

                class U extends T {

                    @Override
                    public int handle(Integer event) {
                        return callCount.incrementAndGet();
                    }
                }

                @Test
                void shouldCallHandlerOnlyOnce() {
                    assertCalledOnlyOnce(new U());
                }
            }
        }
    }

    @Nested
    class GivenAnUnannotatedInterfaceMethod {

        interface I {

            int handle(Integer event);
        }

        @Nested
        class WhenImplementedByAnnotedInstanceMethod {

            class T implements I {

                @Override
                @QueryHandler(queryName = "call-counter")
                public int handle(Integer event) {
                    return callCount.incrementAndGet();
                }
            }

            @Test
            void shouldCallHandlerOnlyOnce() {
                assertCalledOnlyOnce(new T());
            }

            @Nested
            class AndOverriddenAndAnnotatedInASubclass {

                class U extends T {

                    @Override
                    @QueryHandler(queryName = "call-counter")
                    public int handle(Integer event) {
                        return callCount.incrementAndGet();
                    }
                }

                @Test
                void shouldCallHandlerOnlyOnce() {
                    assertCalledOnlyOnce(new U());
                }
            }

            @Nested
            class AndOverriddenButNotAnnotatedInASubclass {

                class U extends T {

                    @Override
                    public int handle(Integer event) {
                        return callCount.incrementAndGet();
                    }
                }

                @Test
                void shouldCallHandlerOnlyOnce() {
                    assertCalledOnlyOnce(new U());
                }
            }
        }

        @Nested
        class WhenImplementedByUnannotedInstanceMethod {

            class T implements I {

                @Override
                public int handle(Integer event) {
                    return callCount.incrementAndGet();
                }
            }

            @Test
            void shouldNotCallAnything() {
                assertNotCalled(new T());
            }

            @Nested
            class AndOverriddenAndAnnotatedInASubclass {

                class U extends T {

                    @Override
                    @QueryHandler(queryName = "call-counter")
                    public int handle(Integer event) {
                        return callCount.incrementAndGet();
                    }
                }

                @Test
                void shouldCallHandlerOnlyOnce() {
                    assertCalledOnlyOnce(new U());
                }
            }

            @Nested
            class AndOverriddenButNotAnnotatedInASubclass {

                class U extends T {

                    @Override
                    public int handle(Integer event) {
                        return callCount.incrementAndGet();
                    }
                }

                @Test
                void shouldNotCallAnything() {
                    assertNotCalled(new U());
                }
            }
        }
    }

    @Nested
    class GivenAnAnnotatedInstanceMethod {

        class T {

            @QueryHandler(queryName = "call-counter")
            public int handle(Integer event) {
                return callCount.incrementAndGet();
            }
        }

        @Test
        void shouldCallHandlerOnlyOnce() {
            assertCalledOnlyOnce(new T());
        }

        @Nested
        class WhenOverriddenAndAnnotatedInASubclass {

            class U extends T {

                @Override
                @QueryHandler(queryName = "call-counter")
                public int handle(Integer event) {
                    return callCount.incrementAndGet();
                }
            }

            @Test
            void shouldCallHandlerOnlyOnce() {
                assertCalledOnlyOnce(new U());
            }
        }

        @Nested
        class WhenNotOverriddenInSubclass {

            class U extends T {

            }

            @Test
            void shouldCallHandlerOnlyOnce() {
                assertCalledOnlyOnce(new U());
            }
        }

        @Nested
        class WhenOverriddenButNotAnnotatedInASubclass {

            class U extends T {

                @Override
                public int handle(Integer event) {
                    return callCount.incrementAndGet();
                }
            }

            @Test
            void shouldCallHandlerOnlyOnce() {
                assertCalledOnlyOnce(new U());
            }
        }
    }

    @Nested
    class GivenAnUnannotatedInstanceMethod {

        class T {

            public int handle(Integer event) {
                return callCount.incrementAndGet();
            }
        }

        @Test
        void shouldNotCallAnything() {
            assertNotCalled(new T());
        }

        @Nested
        class WhenOverriddenAndAnnotatedInASubclass {

            class U extends T {

                @Override
                @QueryHandler(queryName = "call-counter")
                public int handle(Integer event) {
                    return callCount.incrementAndGet();
                }
            }

            @Test
            void shouldCallHandlerOnlyOnce() {
                assertCalledOnlyOnce(new U());
            }
        }

        @Nested
        class WhenOverriddenButNotAnnotatedInASubclass {

            class U extends T {

                @Override
                public int handle(Integer event) {
                    return callCount.incrementAndGet();
                }
            }

            @Test
            void shouldNotCallAnything() {
                assertNotCalled(new U());
            }
        }
    }

    private void assertCalledOnlyOnce(Object handlerInstance) {
        AnnotatedQueryHandlingComponent<?> testSubject = new AnnotatedQueryHandlingComponent<>(
                handlerInstance,
                ClasspathParameterResolverFactory.forClass(handlerInstance.getClass()),
                ClasspathHandlerDefinition.forClass(handlerInstance.getClass()),
                new AnnotationMessageTypeResolver(),
                new DelegatingMessageConverter(PassThroughConverter.INSTANCE)
        );

        QueryMessage testQuery = new GenericQueryMessage(new MessageType("call-counter"), 42);
        ProcessingContext testContext = StubProcessingContext.forMessage(testQuery);

        MessageStream<QueryResponseMessage> stream = testSubject.handle(testQuery, testContext);

        assertThat(stream.hasNextAvailable()).isTrue();

        Object result = stream.first()
                              .asCompletableFuture()
                              .thenApply(MessageStream.Entry::message)
                              .thenApply(Message::payload)
                              .join();

        assertThat(callCount.get()).isEqualTo(1);
        assertThat(result).isEqualTo(1);
    }

    private void assertNotCalled(Object handlerInstance) {
        AnnotatedQueryHandlingComponent<?> testSubject = new AnnotatedQueryHandlingComponent<>(
                handlerInstance,
                ClasspathParameterResolverFactory.forClass(handlerInstance.getClass()),
                ClasspathHandlerDefinition.forClass(handlerInstance.getClass()),
                new AnnotationMessageTypeResolver(),
                new DelegatingMessageConverter(PassThroughConverter.INSTANCE)
        );

        QueryMessage testQuery = new GenericQueryMessage(new MessageType("call-counter"), 42);
        ProcessingContext testContext = StubProcessingContext.forMessage(testQuery);

        MessageStream<QueryResponseMessage> stream = testSubject.handle(testQuery, testContext);

        assertThat(stream.hasNextAvailable()).isFalse();

        CompletableFuture<Entry<QueryResponseMessage>> future = stream.first().asCompletableFuture();

        assertThatThrownBy(() -> future.join())
                .isInstanceOf(CompletionException.class)
                .cause()
                .isInstanceOf(NoHandlerForQueryException.class);

        assertThat(callCount.get()).isEqualTo(0);
    }

    private static class MyQueryHandler {

        @SuppressWarnings("unused")
        @QueryHandler(queryName = "echo")
        public String echo(String echo) {
            return echo;
        }

        @SuppressWarnings("unused")
        @QueryHandler(queryName = "faulty")
        public Integer faulty(String echo) {
            throw new MockException("Mock");
        }

        @SuppressWarnings("unused")
        @QueryHandler(queryName = "emptyOptional")
        public Optional<String> emptyOptional(String echo) {
            return Optional.empty();
        }

        @SuppressWarnings("unused")
        @QueryHandler(queryName = "optional")
        public Optional<String> optional(String echo) {
            return Optional.ofNullable(echo);
        }

        @SuppressWarnings("unused")
        @QueryHandler(queryName = "multipleResponses")
        public List<? extends String> multipleResponses(Integer count) {
            List<String> value = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                value.add("echo");
            }
            return value;
        }

        @SuppressWarnings("unused")
        @QueryHandler(queryName = "nullResponse")
        public String nullResponse(String echo) {
            return null;
        }
    }

    private static class FaultyParameterOrderQueryHandler {

        @SuppressWarnings("unused")
        @QueryHandler
        public String echo(Metadata metadata, String echo) {
            return echo;
        }
    }

    private static class VoidReturnTypeQueryHandler {

        @SuppressWarnings("unused")
        @QueryHandler
        public void echo(String echo) {
        }
    }

    @Test
    void messageTypeResolverIsUsedWhenProvidedAndQueryNameMatchesFullyQualifiedClassName() {
        // given...
        // Create a handler where the queryName uses the default (which would be the payload type's fully qualified name)
        class HandlerWithDefaultQueryName {

            @QueryHandler
            public String handle(String query) {
                return "result";
            }
        }

        MessageTypeResolver customResolver = payloadType -> {
            if (payloadType == String.class) {
                return Optional.of(new MessageType(new QualifiedName("custom.resolved.query")));
            }
            return Optional.empty();
        };

        HandlerWithDefaultQueryName handler = new HandlerWithDefaultQueryName();
        AnnotatedQueryHandlingComponent<?> testSubject = new AnnotatedQueryHandlingComponent<>(
                handler,
                ClasspathParameterResolverFactory.forClass(handler.getClass()),
                ClasspathHandlerDefinition.forClass(handler.getClass()),
                customResolver,
                new DelegatingMessageConverter(PassThroughConverter.INSTANCE)
        );

        // then... the resolver should be used since no custom queryName was specified
        assertThat(testSubject.supportedQueries()).contains(new QualifiedName("custom.resolved.query"));
    }

    @Test
    void queryNameFromAnnotationIsUsedWhenItDoesNotMatchFullyQualifiedClassName() {
        // given...
        // The MyQueryHandler uses custom query names like "echo", "faulty", etc.
        // which don't match the payload type's fully qualified class name
        MyQueryHandler handler = new MyQueryHandler();
        MessageTypeResolver customResolver = payloadType ->
                Optional.of(new MessageType(new QualifiedName("should.not.be.used")));

        AnnotatedQueryHandlingComponent<?> testSubject = new AnnotatedQueryHandlingComponent<>(
                handler,
                ClasspathParameterResolverFactory.forClass(handler.getClass()),
                ClasspathHandlerDefinition.forClass(handler.getClass()),
                customResolver,
                new DelegatingMessageConverter(PassThroughConverter.INSTANCE)
        );

        // then... annotation names should be used, not the resolver
        assertThat(testSubject.supportedQueries()).contains(new QualifiedName("echo"));
        assertThat(testSubject.supportedQueries()).doesNotContain(new QualifiedName("should.not.be.used"));
    }

    @Test
    void queryNameFromAnnotationIsUsedWithAnnotationMessageTypeResolver() {
        // given...
        // When using AnnotationMessageTypeResolver, custom query names should be preserved
        MyQueryHandler handler = new MyQueryHandler();
        AnnotatedQueryHandlingComponent<?> testSubject = new AnnotatedQueryHandlingComponent<>(
                handler,
                ClasspathParameterResolverFactory.forClass(handler.getClass()),
                ClasspathHandlerDefinition.forClass(handler.getClass()),
                new AnnotationMessageTypeResolver(),
                new DelegatingMessageConverter(PassThroughConverter.INSTANCE)
        );

        // then... annotation names should be used since they don't match the fully qualified class name
        assertThat(testSubject.supportedQueries()).contains(new QualifiedName("echo"));
    }
}
