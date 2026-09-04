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

package org.axonframework.modelling.saga;

import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.StubProcessingContext;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.GenericEventMessage;
import org.axonframework.messaging.eventhandling.processing.streaming.segmenting.Segment;
import org.junit.jupiter.api.*;
import org.mockito.*;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

import static java.util.Collections.singleton;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;
import static org.mockito.Mockito.*;

class SagaManagerTest {

    private AbstractSagaManager<Object> testSubject;
    private SagaRepository<Object> mockSagaRepository;
    private Saga<Object> mockSaga1;
    private Saga<Object> mockSaga2;
    private Saga<Object> mockSaga3;
    private AssociationValue associationValue;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        mockSagaRepository = mock(SagaRepository.class);
        mockSaga1 = mock(Saga.class);
        mockSaga2 = mock(Saga.class);
        mockSaga3 = mock(Saga.class);
        when(mockSaga1.isActive()).thenReturn(true);
        when(mockSaga2.isActive()).thenReturn(true);
        when(mockSaga3.isActive()).thenReturn(false);
        when(mockSaga1.getSagaIdentifier()).thenReturn("saga1");
        when(mockSaga2.getSagaIdentifier()).thenReturn("saga2");
        when(mockSaga3.getSagaIdentifier()).thenReturn("saga3");
        when(mockSagaRepository.load(eq("saga1"), any())).thenReturn(mockSaga1);
        when(mockSagaRepository.load(eq("saga2"), any())).thenReturn(mockSaga2);
        when(mockSagaRepository.load(eq("saga3"), any())).thenReturn(mockSaga3);
        when(mockSagaRepository.load(eq("noSaga"), any())).thenReturn(null);
        associationValue = new AssociationValue("association", "value");
        final AssociationValuesImpl associationValues = new AssociationValuesImpl(singleton(associationValue));
        when(mockSaga1.getAssociationValues()).thenReturn(associationValues);
        when(mockSaga2.getAssociationValues()).thenReturn(associationValues);
        when(mockSaga3.getAssociationValues()).thenReturn(associationValues);

        when(mockSaga1.canHandle(any(EventMessage.class), any())).thenReturn(true);
        when(mockSaga2.canHandle(any(EventMessage.class), any())).thenReturn(true);
        when(mockSaga3.canHandle(any(EventMessage.class), any())).thenReturn(true);

        when(mockSaga1.handle(any(EventMessage.class), any())).thenReturn(MessageStream.empty());
        when(mockSaga2.handle(any(EventMessage.class), any())).thenReturn(MessageStream.empty());

        when(mockSagaRepository.find(eq(associationValue), any()))
                .thenReturn(setOf("saga1", "saga2", "saga3", "noSaga"));

        testSubject = TestableAbstractSagaManager.builder()
                                                 .sagaRepository(mockSagaRepository)
                                                 .associationValue(associationValue)
                                                 .build();
    }

    @Test
    void sagasLoaded() {
        EventMessage event = new GenericEventMessage(new MessageType("event"), new Object());
        ProcessingContext context = StubProcessingContext.forMessage(event);

        testSubject.handle(event, context);

        verify(mockSagaRepository).find(associationValue, context);
        verify(mockSaga1).handle(eq(event), any());
        verify(mockSaga2).handle(eq(event), any());
        verify(mockSaga3, never()).handle(eq(event), any());
    }

    @Test
    void exceptionFromOneSagaDoesNotPreventOthersFromBeingInvokedButFailsTheResult() {
        EventMessage event = new GenericEventMessage(new MessageType("event"), new Object());
        ProcessingContext context = StubProcessingContext.forMessage(event);
        RuntimeException toBeThrown = new RuntimeException("Mock");
        when(mockSaga1.handle(eq(event), any())).thenReturn(MessageStream.failed(toBeThrown));

        MessageStream.Empty<Message> result = testSubject.handle(event, context);

        CompletionException exception =
                assertThrows(CompletionException.class, () -> result.asCompletableFuture().join());
        assertEquals(toBeThrown, exception.getCause());
        verify(mockSaga1).handle(eq(event), any());
        verify(mockSaga2).handle(eq(event), any());
        verify(mockSaga3, never()).handle(eq(event), any());
    }

    @SuppressWarnings("unchecked")
    @Test
    void noSagaIsCreatedWhenAnEarlierSagaFailed() {
        // given a policy that always creates, and a saga whose handling fails before creation is reached
        testSubject = TestableAbstractSagaManager.builder()
                                                 .sagaRepository(mockSagaRepository)
                                                 .sagaCreationPolicy(SagaCreationPolicy.ALWAYS)
                                                 .associationValue(associationValue)
                                                 .build();
        EventMessage event = new GenericEventMessage(new MessageType("event"), new Object());
        when(mockSaga1.handle(eq(event), any())).thenReturn(MessageStream.failed(new RuntimeException("Mock")));
        Saga<Object> newSaga = mock(Saga.class);
        when(newSaga.getAssociationValues()).thenReturn(new AssociationValuesImpl());
        when(newSaga.canHandle(any(EventMessage.class), any())).thenReturn(true);
        when(newSaga.handle(any(EventMessage.class), any())).thenReturn(MessageStream.empty());
        when(mockSagaRepository.createInstance(any(), any(), any())).thenReturn(newSaga);

        // when
        MessageStream.Empty<Message> result = testSubject.handle(event, StubProcessingContext.forMessage(event));
        assertThrows(CompletionException.class, () -> result.asCompletableFuture().join());

        // then the new saga was never constructed, so neither its resources nor its handler have run. Axon
        // Framework 4 got this from the exception leaving handle before creation was considered.
        verify(mockSagaRepository, never()).createInstance(any(), any(), any());
    }

    @Test
    void sagaCreatedWhenNoneFoundAndPolicyIsIfNoneFound() {
        testSubject = TestableAbstractSagaManager.builder()
                                                 .sagaRepository(mockSagaRepository)
                                                 .sagaCreationPolicy(SagaCreationPolicy.IF_NONE_FOUND)
                                                 .associationValue(new AssociationValue("someKey", "someValue"))
                                                 .build();

        EventMessage event = new GenericEventMessage(new MessageType("event"), new Object());
        ProcessingContext context = StubProcessingContext.forMessage(event);
        when(mockSaga1.handle(any(EventMessage.class), any())).thenReturn(MessageStream.empty());
        when(mockSagaRepository.createInstance(any(), any(), any())).thenReturn(mockSaga1);
        when(mockSagaRepository.find(any(), any())).thenReturn(Collections.emptySet());

        testSubject.handle(event, context);

        verify(mockSagaRepository).createInstance(any(), any(), any());
        verify(mockSaga1).handle(eq(event), any());
    }

    @Test
    void sagaIsCreatedWhenTheLoadedSagaDeclinesTheEventAndPolicyIsIfNoneFound() {
        // given a saga the repository finds by an association value it no longer holds, so it declines the event
        testSubject = TestableAbstractSagaManager.builder()
                                                 .sagaRepository(mockSagaRepository)
                                                 .sagaCreationPolicy(SagaCreationPolicy.IF_NONE_FOUND)
                                                 .associationValue(associationValue)
                                                 .build();
        when(mockSaga1.canHandle(any(EventMessage.class), any())).thenReturn(false);
        when(mockSaga2.canHandle(any(EventMessage.class), any())).thenReturn(false);
        when(mockSagaRepository.createInstance(any(), any(), any())).thenReturn(mockSaga1);

        // when
        EventMessage event = new GenericEventMessage(new MessageType("event"), new Object());
        testSubject.handle(event, StubProcessingContext.forMessage(event));

        // then no saga of this type took the event, so IF_NONE_FOUND starts a new one
        verify(mockSaga1, never()).handle(eq(event), any());
        verify(mockSaga2, never()).handle(eq(event), any());
        verify(mockSagaRepository).createInstance(any(), any(), any());
    }

    @SuppressWarnings("unchecked")
    @Test
    void aNewSagaThatDeclinesTheEventIsNotInvoked() {
        // given a policy that always creates, and a new saga that declines the event it was created for
        testSubject = TestableAbstractSagaManager.builder()
                                                 .sagaRepository(mockSagaRepository)
                                                 .sagaCreationPolicy(SagaCreationPolicy.ALWAYS)
                                                 .associationValue(new AssociationValue("someKey", "someValue"))
                                                 .build();
        Saga<Object> newSaga = mock(Saga.class);
        when(newSaga.getAssociationValues()).thenReturn(new AssociationValuesImpl());
        when(newSaga.canHandle(any(EventMessage.class), any())).thenReturn(false);
        when(mockSagaRepository.createInstance(any(), any(), any())).thenReturn(newSaga);
        when(mockSagaRepository.find(any(), any())).thenReturn(Collections.emptySet());

        // when
        EventMessage event = new GenericEventMessage(new MessageType("event"), new Object());
        testSubject.handle(event, StubProcessingContext.forMessage(event));

        // then the saga is created and associated, but not invoked
        verify(mockSagaRepository).createInstance(any(), any(), any());
        verify(newSaga, never()).handle(eq(event), any());
    }

    @Test
    void sagaNotCreatedWhenOneWasAlreadyInvokedAndPolicyIsIfNoneFound() {
        testSubject = TestableAbstractSagaManager.builder()
                                                 .sagaRepository(mockSagaRepository)
                                                 .sagaCreationPolicy(SagaCreationPolicy.IF_NONE_FOUND)
                                                 .associationValue(associationValue)
                                                 .build();

        EventMessage event = new GenericEventMessage(new MessageType("event"), new Object());
        ProcessingContext context = StubProcessingContext.forMessage(event);

        testSubject.handle(event, context);

        verify(mockSagaRepository, never()).createInstance(any(), any(), any());
    }

    @Test
    void sagaIsCreatedInRootSegment() {
        testSubject = TestableAbstractSagaManager.builder()
                                                 .sagaRepository(mockSagaRepository)
                                                 .sagaCreationPolicy(SagaCreationPolicy.IF_NONE_FOUND)
                                                 .associationValue(new AssociationValue("someKey", "someValue"))
                                                 .build();

        EventMessage event = new GenericEventMessage(new MessageType("event"), new Object());
        ProcessingContext context = contextFor(event, Segment.ROOT_SEGMENT);
        when(mockSagaRepository.createInstance(any(), any(), any())).thenReturn(mockSaga1);
        when(mockSagaRepository.find(any(), any())).thenReturn(Collections.emptySet());

        testSubject.handle(event, context);

        verify(mockSagaRepository).createInstance(any(), any(), any());
    }

    @Test
    void sagaIsOnlyCreatedInSegmentMatchingAssociationValue() {
        testSubject = TestableAbstractSagaManager.builder()
                                                 .sagaRepository(mockSagaRepository)
                                                 .sagaCreationPolicy(SagaCreationPolicy.IF_NONE_FOUND)
                                                 .associationValue(new AssociationValue("someKey", "someValue"))
                                                 .build();

        Segment[] segments = Segment.ROOT_SEGMENT.split();
        Segment matchingSegment = segments[0].matches("someValue") ? segments[0] : segments[1];
        Segment otherSegment = segments[0].matches("someValue") ? segments[1] : segments[0];

        EventMessage event = new GenericEventMessage(new MessageType("event"), new Object());
        ArgumentCaptor<String> createdSaga = ArgumentCaptor.forClass(String.class);
        when(mockSagaRepository.createInstance(createdSaga.capture(), any(), any())).thenReturn(mockSaga1);
        when(mockSagaRepository.find(any(), any())).thenReturn(Collections.emptySet());

        testSubject.handle(event, contextFor(event, otherSegment));
        verify(mockSagaRepository, never()).createInstance(any(), any(), any());

        testSubject.handle(event, contextFor(event, matchingSegment));
        verify(mockSagaRepository).createInstance(any(), any(), any());

        createdSaga.getAllValues()
                   .forEach(sagaId -> assertTrue(
                           matchingSegment.matches(sagaId),
                           "Saga ID doesn't match segment that should have created it: " + sagaId
                   ));
        createdSaga.getAllValues()
                   .forEach(sagaId -> assertFalse(otherSegment.matches(sagaId),
                                                  "Saga ID matched against the wrong segment: " + sagaId));
    }

    @Test
    void sagaIsNotCreatedIfAssociationValueAndSagaIdMatchDifferentSegments() {
        AssociationValue associationValue = new AssociationValue("someKey", "someValue");
        testSubject = TestableAbstractSagaManager.builder()
                                                 .sagaRepository(mockSagaRepository)
                                                 .sagaCreationPolicy(SagaCreationPolicy.IF_NONE_FOUND)
                                                 .associationValue(associationValue)
                                                 .build();

        // Test won't work if the saga ID and association value map to the same minimum-sized segment.
        assumeTrue((associationValue.hashCode() & Integer.MAX_VALUE) !=
                           (mockSaga1.getSagaIdentifier().hashCode() & Integer.MAX_VALUE));

        EventMessage event = new GenericEventMessage(new MessageType("event"), new Object());

        String sagaId = mockSaga1.getSagaIdentifier();
        when(mockSagaRepository.find(any(), any())).thenReturn(singleton(sagaId));
        when(mockSagaRepository.createInstance(any(), any(), any())).thenReturn(mockSaga2);

        Segment matchesIdSegment = Segment.ROOT_SEGMENT;
        Segment matchesValueSegment;
        do {
            Segment[] segments = matchesIdSegment.split();
            matchesIdSegment = segments[0].matches(sagaId) ? segments[0] : segments[1];
            matchesValueSegment = segments[0].matches(associationValue) ? segments[0] : segments[1];
        } while (matchesIdSegment.equals(matchesValueSegment));

        ProcessingContext contextForIdSegment = contextFor(event, matchesIdSegment);
        testSubject.handle(event, contextForIdSegment);
        testSubject.handle(event, contextFor(event, matchesValueSegment));
        verify(mockSagaRepository, never()).createInstance(any(), any(), any());
        verify(mockSaga1).handle(event, contextForIdSegment);
    }

    private ProcessingContext contextFor(EventMessage event, Segment segment) {
        return StubProcessingContext.forMessage(event).withResource(Segment.RESOURCE_KEY, segment);
    }

    @SuppressWarnings({"unchecked"})
    private <T> Set<T> setOf(T... items) {
        return Set.of(items);
    }

    private static class TestableAbstractSagaManager extends AbstractSagaManager<Object> {

        private final SagaCreationPolicy sagaCreationPolicy;
        private final AssociationValue associationValue;

        private TestableAbstractSagaManager(Builder builder) {
            super(builder);
            this.sagaCreationPolicy = builder.sagaCreationPolicy;
            this.associationValue = builder.associationValue;
        }

        public static Builder builder() {
            return new Builder();
        }

        @Override
        protected boolean canHandle(EventMessage event, ProcessingContext context) {
            return true;
        }

        @Override
        public Set<QualifiedName> supportedEvents() {
            return Set.of(new QualifiedName(Object.class));
        }

        @Override
        protected SagaInitializationPolicy getSagaCreationPolicy(EventMessage event, ProcessingContext context) {
            return new SagaInitializationPolicy(sagaCreationPolicy, associationValue);
        }

        @Override
        protected Set<AssociationValue> extractAssociationValues(EventMessage event, ProcessingContext context) {
            return singleton(associationValue);
        }

        public static class Builder extends AbstractSagaManager.Builder<Object> {

            private SagaCreationPolicy sagaCreationPolicy = SagaCreationPolicy.NONE;
            private AssociationValue associationValue;

            private Builder() {
                super.sagaType(Object.class);
                super.sagaFactory(Object::new);
            }

            @Override
            public Builder sagaRepository(SagaRepository<Object> sagaRepository) {
                super.sagaRepository(sagaRepository);
                return this;
            }

            @Override
            public Builder sagaType(Class<Object> sagaType) {
                super.sagaType(sagaType);
                return this;
            }

            @Override
            public Builder sagaFactory(Supplier<Object> sagaFactory) {
                super.sagaFactory(sagaFactory);
                return this;
            }

            private Builder sagaCreationPolicy(SagaCreationPolicy sagaCreationPolicy) {
                this.sagaCreationPolicy = sagaCreationPolicy;
                return this;
            }

            private Builder associationValue(AssociationValue associationValue) {
                this.associationValue = associationValue;
                return this;
            }

            public TestableAbstractSagaManager build() {
                return new TestableAbstractSagaManager(this);
            }
        }
    }
}
