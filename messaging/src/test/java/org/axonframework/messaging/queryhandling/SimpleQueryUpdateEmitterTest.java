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

package org.axonframework.messaging.queryhandling;

import org.axonframework.common.FutureUtils;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.MessageTypeNotResolvedException;
import org.axonframework.messaging.core.MessageTypeResolver;
import org.axonframework.messaging.core.conversion.MessageConverter;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.StubProcessingContext;
import org.axonframework.conversion.ConversionException;
import org.axonframework.common.util.MockException;
import org.junit.jupiter.api.*;
import org.mockito.*;

import java.lang.reflect.Type;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * Test class validating the {@link SimpleQueryUpdateEmitter}.
 *
 * @author Allard Buijze
 * @author Corrado Musumeci
 * @author Steven van Beelen
 */
class SimpleQueryUpdateEmitterTest {

    private static final MessageType QUERY_PAYLOAD_TYPE = new MessageType("query-type");
    private static final MessageType UPDATE_PAYLOAD_TYPE = new MessageType("update-type");
    private static final Exception CAUSE = new MockException("oops");

    private QueryBus queryBus = QueryBusTestUtils.aQueryBus();
    private MessageTypeResolver messageTypeResolver;
    private MessageConverter converter;
    private ProcessingContext context;

    private SimpleQueryUpdateEmitter testSubject;

    private ArgumentCaptor<Predicate<QueryMessage>> filterCaptor;
    private ArgumentCaptor<Supplier<SubscriptionQueryUpdateMessage>> updateCaptor;

    @BeforeEach
    void setUp() {
        queryBus = mock(QueryBus.class);
        messageTypeResolver = mock(MessageTypeResolver.class);
        converter = mock(MessageConverter.class);
        context = new StubProcessingContext();

        testSubject = new SimpleQueryUpdateEmitter(queryBus, messageTypeResolver, converter, context);

        when(queryBus.emitUpdate(any(), any(), eq(context))).thenReturn(FutureUtils.emptyCompletedFuture());
        when(queryBus.completeSubscriptions(any(), eq(context))).thenReturn(FutureUtils.emptyCompletedFuture());
        when(queryBus.completeSubscriptionsExceptionally(any(), any(), eq(context)))
                .thenReturn(FutureUtils.emptyCompletedFuture());
        when(queryBus.emitUpdateAndCount(any(), any(), eq(context)))
                .thenReturn(CompletableFuture.completedFuture(1));
        when(queryBus.completeSubscriptionsAndCount(any(), eq(context)))
                .thenReturn(CompletableFuture.completedFuture(1));
        when(queryBus.completeSubscriptionsExceptionallyAndCount(any(), any(), eq(context)))
                .thenReturn(CompletableFuture.completedFuture(1));
        filterCaptor = ArgumentCaptor.captor();
        updateCaptor = ArgumentCaptor.captor();
    }

    @Nested
    class Construction {

        @Test
        void throwsNullPointerExceptionForNullQueryBus() {
            //noinspection DataFlowIssue
            assertThatThrownBy(() -> new SimpleQueryUpdateEmitter(
                    null, messageTypeResolver, converter, context
            )).isInstanceOf(NullPointerException.class);
        }

        @Test
        void throwsNullPointerExceptionForNullMessageTypeResolver() {
            //noinspection DataFlowIssue
            assertThatThrownBy(() -> new SimpleQueryUpdateEmitter(
                    queryBus, null, converter, context
            )).isInstanceOf(NullPointerException.class);
        }

        @Test
        void throwsNullPointerExceptionForNullMessageConverter() {
            //noinspection DataFlowIssue
            assertThatThrownBy(() -> new SimpleQueryUpdateEmitter(
                    queryBus, messageTypeResolver, null, context
            )).isInstanceOf(NullPointerException.class);
        }

        @Test
        void throwsNullPointerExceptionForNullApplicationContext() {
            //noinspection DataFlowIssue
            assertThatThrownBy(() -> new SimpleQueryUpdateEmitter(
                    queryBus, messageTypeResolver, converter, null
            )).isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    class EmittingUpdates {

        @Test
        void emitForQueryType() {
            // given...
            QueryMessage testQuery = new GenericQueryMessage(
                    QUERY_PAYLOAD_TYPE, "some-query");
            Update testUpdatePayload = new Update("some-update");
            when(messageTypeResolver.resolveOrThrow(String.class)).thenReturn(QUERY_PAYLOAD_TYPE);
            when(messageTypeResolver.resolveOrThrow(testUpdatePayload)).thenReturn(UPDATE_PAYLOAD_TYPE);
            // when...
            testSubject.emit(String.class, query -> true, testUpdatePayload);
            // then...
            verify(queryBus).emitUpdate(filterCaptor.capture(), updateCaptor.capture(), eq(context));
            assertThat(filterCaptor.getValue().test(testQuery)).isTrue();
            Supplier<SubscriptionQueryUpdateMessage> resultUpdateSupplier = updateCaptor.getValue();
            SubscriptionQueryUpdateMessage resultUpdate = resultUpdateSupplier.get();
            assertThat(resultUpdate.type()).isEqualTo(UPDATE_PAYLOAD_TYPE);
            assertThat(resultUpdate.payload()).isEqualTo(testUpdatePayload);
            verify(messageTypeResolver).resolveOrThrow(testUpdatePayload);
            verifyNoInteractions(converter);
        }

        @Test
        void emitForQueryTypeAndFilter() {
            // given...
            SubscriptionQuery testQueryPayload = new SubscriptionQuery("some-query");
            QueryMessage testQuery = new GenericQueryMessage(
                    QUERY_PAYLOAD_TYPE, testQueryPayload);
            Update testUpdatePayload = new Update("some-update");

            when(messageTypeResolver.resolveOrThrow(String.class)).thenReturn(QUERY_PAYLOAD_TYPE);
            when(messageTypeResolver.resolveOrThrow(testUpdatePayload)).thenReturn(UPDATE_PAYLOAD_TYPE);
            when(converter.convert(any(), (Type) eq(String.class))).thenReturn("some-query");
            // when...
            testSubject.emit(String.class, query -> query.equals("some-query"), testUpdatePayload);
            // then...
            verify(queryBus).emitUpdate(filterCaptor.capture(), updateCaptor.capture(), eq(context));
            assertThat(filterCaptor.getValue().test(testQuery)).isTrue();
            Supplier<SubscriptionQueryUpdateMessage> resultUpdateSupplier = updateCaptor.getValue();
            SubscriptionQueryUpdateMessage resultUpdate = resultUpdateSupplier.get();
            assertThat(resultUpdate.type()).isEqualTo(UPDATE_PAYLOAD_TYPE);
            assertThat(resultUpdate.payload()).isEqualTo(testUpdatePayload);

            verify(messageTypeResolver).resolveOrThrow(String.class);
            verify(messageTypeResolver).resolveOrThrow(testUpdatePayload);
            verify(converter).convert(testQueryPayload, (Type) String.class);
        }

        @Test
        void emitForQueryTypeThrowsMessageTypeNotResolvedExceptionDuringFilter() {
            // given...
            SubscriptionQuery testQueryPayload = new SubscriptionQuery("some-query");
            QueryMessage testQuery = new GenericQueryMessage(
                    QUERY_PAYLOAD_TYPE, testQueryPayload);
            Update testUpdatePayload = new Update("some-update");

            when(messageTypeResolver.resolveOrThrow(any()))
                    .thenThrow(MessageTypeNotResolvedException.class);
            when(messageTypeResolver.resolveOrThrow(testUpdatePayload)).thenReturn(UPDATE_PAYLOAD_TYPE);
            when(converter.convert(any(), (Type) eq(String.class))).thenReturn("some-query");
            // when...
            testSubject.emit(String.class, query -> query.equals("some-query"), testUpdatePayload);
            // then...
            //noinspection unchecked
            verify(queryBus).emitUpdate(filterCaptor.capture(), any(Supplier.class), eq(context));
            assertThatThrownBy(() -> filterCaptor.getValue().test(testQuery))
                    .isInstanceOf(MessageTypeNotResolvedException.class);
        }

        @Test
        void emitForQueryTypeThrowsConversionExceptionDuringFilter() {
            // given...
            SubscriptionQuery testQueryPayload = new SubscriptionQuery("some-query");
            QueryMessage testQuery = new GenericQueryMessage(
                    QUERY_PAYLOAD_TYPE, testQueryPayload);
            Update testUpdatePayload = new Update("some-update");

            when(messageTypeResolver.resolveOrThrow(String.class)).thenReturn(QUERY_PAYLOAD_TYPE);
            when(messageTypeResolver.resolveOrThrow(testUpdatePayload)).thenReturn(UPDATE_PAYLOAD_TYPE);
            when(converter.convert(any(), (Type) eq(String.class))).thenThrow(ConversionException.class);
            // when...
            testSubject.emit(String.class, query -> query.equals("some-query"), testUpdatePayload);
            // then...
            verify(queryBus).emitUpdate(filterCaptor.capture(), any(), eq(context));
            assertThatThrownBy(() -> filterCaptor.getValue().test(testQuery))
                    .isInstanceOf(ConversionException.class);
        }

        @Test
        void emitForQueryTypeDoesNotRetrieveUpdateWhenNoQueriesMatch() {
            // given...
            SubscriptionQuery testQueryPayload = new SubscriptionQuery("some-query");
            QueryMessage testQuery = new GenericQueryMessage(
                    QUERY_PAYLOAD_TYPE, testQueryPayload);
            AtomicBoolean updateSupplierInvoked = new AtomicBoolean(false);
            when(messageTypeResolver.resolveOrThrow(String.class)).thenReturn(QUERY_PAYLOAD_TYPE);
            // when...
            testSubject.emit(String.class, query -> false, () -> {
                updateSupplierInvoked.set(true);
                return "this should never be returned!";
            });
            // then...
            verify(queryBus).emitUpdate(filterCaptor.capture(), updateCaptor.capture(), eq(context));
            assertThat(filterCaptor.getValue().test(testQuery)).isFalse();
            assertThat(updateSupplierInvoked).isFalse();
        }

        @Test
        void emitForQueryName() {
            // given...
            QueryMessage testQuery = new GenericQueryMessage(
                    QUERY_PAYLOAD_TYPE, "some-query");
            Update testUpdatePayload = new Update("some-update");
            when(messageTypeResolver.resolveOrThrow(testUpdatePayload)).thenReturn(UPDATE_PAYLOAD_TYPE);
            // when...
            testSubject.emit(QUERY_PAYLOAD_TYPE.qualifiedName(), query -> true, testUpdatePayload);
            // then...
            verify(queryBus).emitUpdate(filterCaptor.capture(), updateCaptor.capture(), eq(context));
            assertThat(filterCaptor.getValue().test(testQuery)).isTrue();
            Supplier<SubscriptionQueryUpdateMessage> resultUpdateSupplier = updateCaptor.getValue();
            SubscriptionQueryUpdateMessage resultUpdate = resultUpdateSupplier.get();
            assertThat(resultUpdate.type()).isEqualTo(UPDATE_PAYLOAD_TYPE);
            assertThat(resultUpdate.payload()).isEqualTo(testUpdatePayload);
            verify(messageTypeResolver).resolveOrThrow(testUpdatePayload);
            verifyNoInteractions(converter);
        }

        @Test
        void emitForQueryNameAndGivenFilter() {
            // given...
            SubscriptionQuery testQueryPayload = new SubscriptionQuery("some-query");
            QueryMessage testQuery = new GenericQueryMessage(
                    QUERY_PAYLOAD_TYPE, testQueryPayload);
            Update testUpdatePayload = new Update("some-update");

            when(messageTypeResolver.resolveOrThrow(String.class)).thenReturn(QUERY_PAYLOAD_TYPE);
            when(messageTypeResolver.resolveOrThrow(testUpdatePayload)).thenReturn(UPDATE_PAYLOAD_TYPE);
            // when...
            testSubject.emit(QUERY_PAYLOAD_TYPE.qualifiedName(),
                             query -> query.equals(testQueryPayload),
                             testUpdatePayload);
            // then...
            verify(queryBus).emitUpdate(filterCaptor.capture(), updateCaptor.capture(), eq(context));
            assertThat(filterCaptor.getValue().test(testQuery)).isTrue();
            Supplier<SubscriptionQueryUpdateMessage> resultUpdateSupplier = updateCaptor.getValue();
            SubscriptionQueryUpdateMessage resultUpdate = resultUpdateSupplier.get();
            assertThat(resultUpdate.type()).isEqualTo(UPDATE_PAYLOAD_TYPE);
            assertThat(resultUpdate.payload()).isEqualTo(testUpdatePayload);

            verify(messageTypeResolver).resolveOrThrow(testUpdatePayload);
            verifyNoInteractions(converter);
        }

        @Test
        void emitForQueryNameDoesNotRetrieveUpdateWhenNoQueriesMatch() {
            // given...
            SubscriptionQuery testQueryPayload = new SubscriptionQuery("some-query");
            QueryMessage testQuery = new GenericQueryMessage(
                    QUERY_PAYLOAD_TYPE, testQueryPayload);
            AtomicBoolean updateSupplierInvoked = new AtomicBoolean(false);
            when(messageTypeResolver.resolveOrThrow(String.class)).thenReturn(QUERY_PAYLOAD_TYPE);
            // when...
            testSubject.emit(QUERY_PAYLOAD_TYPE.qualifiedName(), query -> false, () -> {
                updateSupplierInvoked.set(true);
                return "this should never be returned!";
            });
            // then...
            verify(queryBus).emitUpdate(filterCaptor.capture(), updateCaptor.capture(), eq(context));
            assertThat(filterCaptor.getValue().test(testQuery)).isFalse();
            assertThat(updateSupplierInvoked).isFalse();

            verify(messageTypeResolver, times(0)).resolveOrThrow(String.class);
            verifyNoInteractions(converter);
        }

        @Test
        void emitReturningCountForQueryTypeReturnsMatchCountFromQueryBus() {
            // given...
            QueryMessage testQuery = new GenericQueryMessage(QUERY_PAYLOAD_TYPE, "some-query");
            Update testUpdatePayload = new Update("some-update");
            when(messageTypeResolver.resolveOrThrow(String.class)).thenReturn(QUERY_PAYLOAD_TYPE);
            when(messageTypeResolver.resolveOrThrow(testUpdatePayload)).thenReturn(UPDATE_PAYLOAD_TYPE);
            when(queryBus.emitUpdateAndCount(any(), any(), eq(context)))
                    .thenReturn(CompletableFuture.completedFuture(3));
            // when...
            int matchCount = testSubject.emitAndCount(String.class, query -> true, () -> testUpdatePayload);
            // then...
            assertThat(matchCount).isEqualTo(3);
            verify(queryBus).emitUpdateAndCount(filterCaptor.capture(), updateCaptor.capture(), eq(context));
            assertThat(filterCaptor.getValue().test(testQuery)).isTrue();
        }

        @Test
        void emitReturningCountForQueryNameReturnsMatchCountFromQueryBus() {
            // given...
            QueryMessage testQuery = new GenericQueryMessage(QUERY_PAYLOAD_TYPE, "some-query");
            Update testUpdatePayload = new Update("some-update");
            when(messageTypeResolver.resolveOrThrow(testUpdatePayload)).thenReturn(UPDATE_PAYLOAD_TYPE);
            when(queryBus.emitUpdateAndCount(any(), any(), eq(context)))
                    .thenReturn(CompletableFuture.completedFuture(2));
            // when...
            int matchCount = testSubject.emitAndCount(QUERY_PAYLOAD_TYPE.qualifiedName(),
                                                            query -> true,
                                                      () -> testUpdatePayload);
            // then...
            assertThat(matchCount).isEqualTo(2);
            verify(queryBus).emitUpdateAndCount(filterCaptor.capture(), updateCaptor.capture(), eq(context));
            assertThat(filterCaptor.getValue().test(testQuery)).isTrue();
        }

        @Test
        void emitReturningCountPropagatesTheOriginalExceptionThrownByTheQueryBus() {
            // given...
            when(queryBus.emitUpdateAndCount(any(), any(), eq(context)))
                    .thenReturn(CompletableFuture.failedFuture(CAUSE));
            // when/then...
            assertThatThrownBy(() -> testSubject.emitAndCount(String.class, query -> true, () -> "update"))
                    .isSameAs(CAUSE);
        }
    }

    @Nested
    class Complete {

        @Test
        void completeForQueryType() {
            // given...
            QueryMessage testQuery = new GenericQueryMessage(
                    QUERY_PAYLOAD_TYPE, "some-query");
            when(messageTypeResolver.resolveOrThrow(String.class)).thenReturn(QUERY_PAYLOAD_TYPE);
            // when...
            testSubject.complete(String.class, query -> true);
            // then...
            verify(queryBus).completeSubscriptions(filterCaptor.capture(), eq(context));
            assertThat(filterCaptor.getValue().test(testQuery)).isTrue();
            verify(messageTypeResolver).resolveOrThrow(String.class);
            verifyNoInteractions(converter);
        }

        @Test
        void completeForQueryTypeAndFilter() {
            // given...
            SubscriptionQuery testQueryPayload = new SubscriptionQuery("some-query");
            QueryMessage testQuery = new GenericQueryMessage(
                    QUERY_PAYLOAD_TYPE, testQueryPayload);

            when(messageTypeResolver.resolveOrThrow(String.class)).thenReturn(QUERY_PAYLOAD_TYPE);
            when(converter.convert(any(), (Type) eq(String.class))).thenReturn("some-query");
            // when...
            testSubject.complete(String.class, query -> query.equals("some-query"));
            // then...
            verify(queryBus).completeSubscriptions(filterCaptor.capture(), eq(context));
            assertThat(filterCaptor.getValue().test(testQuery)).isTrue();
            verify(messageTypeResolver).resolveOrThrow(String.class);
            verify(converter).convert(testQueryPayload, (Type) String.class);
        }

        @Test
        void completeForQueryTypeThrowsMessageTypeNotResolvedExceptionDuringFilter() {
            // given...
            SubscriptionQuery testQueryPayload = new SubscriptionQuery("some-query");
            QueryMessage testQuery = new GenericQueryMessage(
                    QUERY_PAYLOAD_TYPE, testQueryPayload);

            when(messageTypeResolver.resolveOrThrow(any()))
                    .thenThrow(MessageTypeNotResolvedException.class);
            when(converter.convert(any(), (Type) eq(String.class))).thenReturn("some-query");
            // when...
            testSubject.complete(String.class, query -> query.equals("some-query"));
            // then...
            verify(queryBus).completeSubscriptions(filterCaptor.capture(), eq(context));
            assertThatThrownBy(() -> filterCaptor.getValue().test(testQuery))
                    .isInstanceOf(MessageTypeNotResolvedException.class);
        }

        @Test
        void completeForQueryTypeThrowsConversionExceptionDuringFilter() {
            // given...
            SubscriptionQuery testQueryPayload = new SubscriptionQuery("some-query");
            QueryMessage testQuery = new GenericQueryMessage(
                    QUERY_PAYLOAD_TYPE, testQueryPayload);

            when(messageTypeResolver.resolveOrThrow(String.class)).thenReturn(QUERY_PAYLOAD_TYPE);
            when(converter.convert(any(), (Type) eq(String.class))).thenThrow(ConversionException.class);
            // when...
            testSubject.complete(String.class, query -> query.equals("some-query"));
            // then...
            verify(queryBus).completeSubscriptions(filterCaptor.capture(), eq(context));
            assertThatThrownBy(() -> filterCaptor.getValue().test(testQuery))
                    .isInstanceOf(ConversionException.class);
        }

        @Test
        void completeForQueryName() {
            // given...
            QueryMessage testQuery = new GenericQueryMessage(
                    QUERY_PAYLOAD_TYPE, "some-query");
            // when...
            testSubject.complete(QUERY_PAYLOAD_TYPE.qualifiedName(), query -> true);
            // then...
            verify(queryBus).completeSubscriptions(filterCaptor.capture(), eq(context));
            assertThat(filterCaptor.getValue().test(testQuery)).isTrue();

            verifyNoInteractions(messageTypeResolver);
            verifyNoInteractions(converter);
        }

        @Test
        void completeForQueryNameAndGivenFilter() {
            // given...
            SubscriptionQuery testQueryPayload = new SubscriptionQuery("some-query");
            QueryMessage testQuery = new GenericQueryMessage(
                    QUERY_PAYLOAD_TYPE, testQueryPayload);
            Update testUpdatePayload = new Update("some-update");

            when(messageTypeResolver.resolveOrThrow(String.class)).thenReturn(QUERY_PAYLOAD_TYPE);
            when(messageTypeResolver.resolveOrThrow(testUpdatePayload)).thenReturn(UPDATE_PAYLOAD_TYPE);
            // when...
            testSubject.complete(QUERY_PAYLOAD_TYPE.qualifiedName(), query -> query.equals(testQueryPayload));
            // then...
            verify(queryBus).completeSubscriptions(filterCaptor.capture(), eq(context));
            assertThat(filterCaptor.getValue().test(testQuery)).isTrue();

            verifyNoInteractions(messageTypeResolver);
            verifyNoInteractions(converter);
        }

        @Test
        void completeReturningCountForQueryTypeReturnsMatchCountFromQueryBus() {
            // given...
            QueryMessage testQuery = new GenericQueryMessage(QUERY_PAYLOAD_TYPE, "some-query");
            when(messageTypeResolver.resolveOrThrow(String.class)).thenReturn(QUERY_PAYLOAD_TYPE);
            when(queryBus.completeSubscriptionsAndCount(any(), eq(context)))
                    .thenReturn(CompletableFuture.completedFuture(3));
            // when...
            int matchCount = testSubject.completeAndCount(String.class, query -> true);
            // then...
            assertThat(matchCount).isEqualTo(3);
            verify(queryBus).completeSubscriptionsAndCount(filterCaptor.capture(), eq(context));
            assertThat(filterCaptor.getValue().test(testQuery)).isTrue();
        }

        @Test
        void completeReturningCountForQueryNameReturnsMatchCountFromQueryBus() {
            // given...
            QueryMessage testQuery = new GenericQueryMessage(QUERY_PAYLOAD_TYPE, "some-query");
            when(queryBus.completeSubscriptionsAndCount(any(), eq(context)))
                    .thenReturn(CompletableFuture.completedFuture(2));
            // when...
            int matchCount = testSubject.completeAndCount(QUERY_PAYLOAD_TYPE.qualifiedName(), query -> true);
            // then...
            assertThat(matchCount).isEqualTo(2);
            verify(queryBus).completeSubscriptionsAndCount(filterCaptor.capture(), eq(context));
            assertThat(filterCaptor.getValue().test(testQuery)).isTrue();
        }
    }

    @Nested
    class CompleteExceptionally {

        @Test
        void completeExceptionallyForQueryType() {
            // given...
            QueryMessage testQuery = new GenericQueryMessage(
                    QUERY_PAYLOAD_TYPE, "some-query");
            when(messageTypeResolver.resolveOrThrow(String.class)).thenReturn(QUERY_PAYLOAD_TYPE);
            // when...
            testSubject.completeExceptionally(String.class, query -> true, CAUSE);
            // then...
            verify(queryBus).completeSubscriptionsExceptionally(filterCaptor.capture(), eq(CAUSE), eq(context));
            assertThat(filterCaptor.getValue().test(testQuery)).isTrue();
            verify(messageTypeResolver).resolveOrThrow(String.class);
            verifyNoInteractions(converter);
        }

        @Test
        void completeExceptionallyForQueryTypeAndFilter() {
            // given...
            SubscriptionQuery testQueryPayload = new SubscriptionQuery("some-query");
            QueryMessage testQuery = new GenericQueryMessage(
                    QUERY_PAYLOAD_TYPE, testQueryPayload);

            when(messageTypeResolver.resolveOrThrow(String.class)).thenReturn(QUERY_PAYLOAD_TYPE);
            when(converter.convert(any(), (Type) eq(String.class))).thenReturn("some-query");
            // when...
            testSubject.completeExceptionally(String.class, query -> query.equals("some-query"), CAUSE);
            // then...
            verify(queryBus).completeSubscriptionsExceptionally(filterCaptor.capture(), eq(CAUSE), eq(context));
            assertThat(filterCaptor.getValue().test(testQuery)).isTrue();
            verify(messageTypeResolver).resolveOrThrow(String.class);
            verify(converter).convert(testQueryPayload, (Type) String.class);
        }

        @Test
        void completeExceptionallyForQueryTypeThrowsMessageTypeNotResolvedExceptionDuringFilter() {
            // given...
            SubscriptionQuery testQueryPayload = new SubscriptionQuery("some-query");
            QueryMessage testQuery = new GenericQueryMessage(
                    QUERY_PAYLOAD_TYPE, testQueryPayload);

            when(messageTypeResolver.resolveOrThrow(any()))
                    .thenThrow(MessageTypeNotResolvedException.class);
            when(converter.convert(any(), (Type) eq(String.class))).thenReturn("some-query");
            // when...
            testSubject.completeExceptionally(String.class, query -> query.equals("some-query"), CAUSE);
            // then...
            verify(queryBus).completeSubscriptionsExceptionally(filterCaptor.capture(), eq(CAUSE), eq(context));
            assertThatThrownBy(() -> filterCaptor.getValue().test(testQuery))
                    .isInstanceOf(MessageTypeNotResolvedException.class);
        }

        @Test
        void completeExceptionallyForQueryTypeThrowsConversionExceptionDuringFilter() {
            // given...
            SubscriptionQuery testQueryPayload = new SubscriptionQuery("some-query");
            QueryMessage testQuery = new GenericQueryMessage(
                    QUERY_PAYLOAD_TYPE, testQueryPayload);

            when(messageTypeResolver.resolveOrThrow(String.class)).thenReturn(QUERY_PAYLOAD_TYPE);
            when(converter.convert(any(), (Type) eq(String.class))).thenThrow(ConversionException.class);
            // when...
            testSubject.completeExceptionally(String.class, query -> query.equals("some-query"), CAUSE);
            // then...
            verify(queryBus).completeSubscriptionsExceptionally(filterCaptor.capture(), eq(CAUSE), eq(context));
            assertThatThrownBy(() -> filterCaptor.getValue().test(testQuery))
                    .isInstanceOf(ConversionException.class);
        }

        @Test
        void completeExceptionallyForQueryName() {
            // given...
            QueryMessage testQuery = new GenericQueryMessage(
                    QUERY_PAYLOAD_TYPE, "some-query");
            // when...
            testSubject.completeExceptionally(QUERY_PAYLOAD_TYPE.qualifiedName(), query -> true, CAUSE);
            // then...
            verify(queryBus).completeSubscriptionsExceptionally(filterCaptor.capture(), eq(CAUSE), eq(context));
            assertThat(filterCaptor.getValue().test(testQuery)).isTrue();

            verifyNoInteractions(messageTypeResolver);
            verifyNoInteractions(converter);
        }

        @Test
        void completeExceptionallyForQueryNameAndGivenFilter() {
            // given...
            SubscriptionQuery testQueryPayload = new SubscriptionQuery("some-query");
            QueryMessage testQuery = new GenericQueryMessage(
                    QUERY_PAYLOAD_TYPE, testQueryPayload);
            Update testUpdatePayload = new Update("some-update");

            when(messageTypeResolver.resolveOrThrow(String.class)).thenReturn(QUERY_PAYLOAD_TYPE);
            when(messageTypeResolver.resolveOrThrow(testUpdatePayload)).thenReturn(UPDATE_PAYLOAD_TYPE);
            // when...
            testSubject.completeExceptionally(QUERY_PAYLOAD_TYPE.qualifiedName(),
                                              query -> query.equals(testQueryPayload),
                                              CAUSE);
            // then...
            verify(queryBus).completeSubscriptionsExceptionally(filterCaptor.capture(), eq(CAUSE), eq(context));
            assertThat(filterCaptor.getValue().test(testQuery)).isTrue();

            verifyNoInteractions(messageTypeResolver);
            verifyNoInteractions(converter);
        }

        @Test
        void completeExceptionallyReturningCountForQueryTypeReturnsMatchCountFromQueryBus() {
            // given...
            QueryMessage testQuery = new GenericQueryMessage(QUERY_PAYLOAD_TYPE, "some-query");
            when(messageTypeResolver.resolveOrThrow(String.class)).thenReturn(QUERY_PAYLOAD_TYPE);
            when(queryBus.completeSubscriptionsExceptionallyAndCount(any(), any(), eq(context)))
                    .thenReturn(CompletableFuture.completedFuture(3));
            // when...
            int matchCount = testSubject.completeExceptionallyAndCount(String.class, query -> true, CAUSE);
            // then...
            assertThat(matchCount).isEqualTo(3);
            verify(queryBus).completeSubscriptionsExceptionallyAndCount(filterCaptor.capture(),
                                                                        eq(CAUSE),
                                                                        eq(context));
            assertThat(filterCaptor.getValue().test(testQuery)).isTrue();
        }

        @Test
        void completeExceptionallyReturningCountForQueryNameReturnsMatchCountFromQueryBus() {
            // given...
            QueryMessage testQuery = new GenericQueryMessage(QUERY_PAYLOAD_TYPE, "some-query");
            when(queryBus.completeSubscriptionsExceptionallyAndCount(any(), any(), eq(context)))
                    .thenReturn(CompletableFuture.completedFuture(2));
            // when...
            int matchCount = testSubject.completeExceptionallyAndCount(QUERY_PAYLOAD_TYPE.qualifiedName(),
                                                                             query -> true,
                                                                       CAUSE);
            // then...
            assertThat(matchCount).isEqualTo(2);
            verify(queryBus).completeSubscriptionsExceptionallyAndCount(filterCaptor.capture(),
                                                                        eq(CAUSE),
                                                                        eq(context));
            assertThat(filterCaptor.getValue().test(testQuery)).isTrue();
        }
    }

    private record SubscriptionQuery(String value) {

    }

    private record Update(String value) {

    }
}
