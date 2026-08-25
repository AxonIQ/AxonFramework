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

import org.jspecify.annotations.NonNull;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.GenericEventMessage;
import org.axonframework.messaging.eventhandling.processing.errorhandling.ListenerInvocationErrorHandler;
import org.axonframework.messaging.eventhandling.processing.streaming.segmenting.Segment;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.ResultMessage;
import org.axonframework.messaging.unitofwork.LegacyDefaultUnitOfWork;
import org.axonframework.messaging.unitofwork.LegacyUnitOfWork;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.StubProcessingContext;
import org.axonframework.messaging.tracing.support.TestSpanFactory;
import org.axonframework.common.util.MockException;
import org.junit.jupiter.api.*;
import org.mockito.*;

import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.Supplier;

import static java.util.Collections.singleton;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;
import static org.mockito.Mockito.*;

class SagaManagerTest {

    private AbstractSagaManager<Object> testSubject;
    private SagaRepository<Object> mockSagaRepository;
    private ListenerInvocationErrorHandler mockErrorHandler;
    private Saga<Object> mockSaga1;
    private Saga<Object> mockSaga2;
    private Saga<Object> mockSaga3;
    private AssociationValue associationValue;
    private TestSpanFactory spanFactory;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        spanFactory = new TestSpanFactory();
        mockSagaRepository = mock(SagaRepository.class);
        mockSaga1 = mock(Saga.class);
        mockSaga2 = mock(Saga.class);
        mockSaga3 = mock(Saga.class);
        mockErrorHandler = mock(ListenerInvocationErrorHandler.class);
        when(mockSaga1.isActive()).thenReturn(true);
        when(mockSaga2.isActive()).thenReturn(true);
        when(mockSaga3.isActive()).thenReturn(false);
        when(mockSaga1.getSagaIdentifier()).thenReturn("saga1");
        when(mockSaga2.getSagaIdentifier()).thenReturn("saga2");
        when(mockSaga3.getSagaIdentifier()).thenReturn("saga3");
        when(mockSagaRepository.load("saga1")).thenReturn(mockSaga1);
        when(mockSagaRepository.load("saga2")).thenReturn(mockSaga2);
        when(mockSagaRepository.load("saga3")).thenReturn(mockSaga3);
        when(mockSagaRepository.load("noSaga")).thenReturn(null);
        associationValue = new AssociationValue("association", "value");
        final AssociationValuesImpl associationValues = new AssociationValuesImpl(singleton(associationValue));
        when(mockSaga1.getAssociationValues()).thenReturn(associationValues);
        when(mockSaga2.getAssociationValues()).thenReturn(associationValues);
        when(mockSaga3.getAssociationValues()).thenReturn(associationValues);

        when(mockSaga1.canHandle(any(EventMessage.class), any())).thenReturn(true);
        when(mockSaga2.canHandle(any(EventMessage.class), any())).thenReturn(true);

        when(mockSagaRepository.find(eq(associationValue)))
                .thenReturn(setOf("saga1", "saga2", "saga3", "noSaga"));

        testSubject = TestableAbstractSagaManager.builder()
                                                 .sagaRepository(mockSagaRepository)
                                                 .listenerInvocationErrorHandler(mockErrorHandler)
                                                 .associationValue(associationValue)
                                                 .spanFactory(
                                                         DefaultSagaManagerSpanFactory.builder()
                                                                                      .spanFactory(spanFactory)
                                                                                      .build())
                                                 .build();
    }

    @Test
    void sagasLoaded() throws Exception {
        EventMessage event = new GenericEventMessage(new MessageType("event"), new Object());
        LegacyUnitOfWork<? extends EventMessage> unitOfWork = new LegacyDefaultUnitOfWork<>(event);
        unitOfWork.executeWithResult((ctx) -> {
            testSubject.handle(event, ctx, Segment.ROOT_SEGMENT);
            return null;
        });
        verify(mockSagaRepository).find(associationValue);
        verify(mockSaga1).handleSync(eq(event), any());
        verify(mockSaga2).handleSync(eq(event), any());
        verify(mockSaga3, never()).handleSync(eq(event), any());
    }

    @Test
    void sagaIsTraced() {
        EventMessage event = new GenericEventMessage(new MessageType("event"), new Object());
        LegacyUnitOfWork<? extends EventMessage> unitOfWork = new LegacyDefaultUnitOfWork<>(event);
        unitOfWork.executeWithResult((ctx) -> {
            testSubject.handle(event, ctx, Segment.ROOT_SEGMENT);
            return null;
        });
        spanFactory.verifySpanCompleted("SagaManager.invokeSaga(Object)");
        spanFactory.verifySpanHasAttributeValue("SagaManager.invokeSaga(Object)", "axon.sagaIdentifier", "saga1");
        spanFactory.verifySpanCompleted("SagaManager.invokeSaga(Object)");
        spanFactory.verifySpanHasAttributeValue("SagaManager.invokeSaga(Object)", "axon.sagaIdentifier", "saga2");
    }

    @Test
    void sagaIsTracedForCreation() throws Exception {
        testSubject = TestableAbstractSagaManager.builder()
                                                 .sagaRepository(mockSagaRepository)
                                                 .listenerInvocationErrorHandler(mockErrorHandler)
                                                 .sagaCreationPolicy(SagaCreationPolicy.IF_NONE_FOUND)
                                                 .associationValue(new AssociationValue("someKey", "someValue"))
                                                 .spanFactory(DefaultSagaManagerSpanFactory.builder()
                                                                                           .spanFactory(spanFactory)
                                                                                           .build())
                                                 .build();

        EventMessage event = new GenericEventMessage(new MessageType("event"), new Object());
        when(mockSagaRepository.createInstance(any(), any())).thenReturn(mockSaga1);
        when(mockSagaRepository.find(any())).thenReturn(Collections.emptySet());

        testSubject.handle(event, null, Segment.ROOT_SEGMENT);
        spanFactory.verifySpanCompleted("SagaManager.createSaga(Object)");
        spanFactory.verifySpanCompleted("SagaManager.invokeSaga(Object)");
        spanFactory.verifySpanHasAttributeValue("SagaManager.invokeSaga(Object)", "axon.sagaIdentifier", "saga1");
    }

    @Test
    void exceptionPropagated() throws Exception {
        EventMessage event = new GenericEventMessage(new MessageType("event"), new Object());
        MockException toBeThrown = new MockException();
        doThrow(toBeThrown).when(mockSaga1).handleSync(eq(event), any());
        doThrow(toBeThrown).when(mockErrorHandler).onError(toBeThrown, event, mockSaga1);
        LegacyUnitOfWork<? extends EventMessage> unitOfWork = new LegacyDefaultUnitOfWork<>(event);
        ResultMessage resultMessage = unitOfWork.executeWithResult((ctx) -> {
            testSubject.handle(event, ctx, Segment.ROOT_SEGMENT);
            return null;
        });
        if (resultMessage.payload() instanceof Throwable e) {
            assertEquals("Mock", e.getMessage());
        } else {
            fail("Expected exception to be propagated");
        }
        verify(mockSaga1, times(1)).handleSync(eq(event), any());
        verify(mockErrorHandler).onError(toBeThrown, event, mockSaga1);
    }

    @Test
    void sagaIsCreatedInRootSegment() throws Exception {
        testSubject = TestableAbstractSagaManager.builder()
                                                 .sagaRepository(mockSagaRepository)
                                                 .listenerInvocationErrorHandler(mockErrorHandler)
                                                 .sagaCreationPolicy(SagaCreationPolicy.IF_NONE_FOUND)
                                                 .associationValue(new AssociationValue("someKey", "someValue"))
                                                 .build();

        EventMessage event = new GenericEventMessage(new MessageType("event"), new Object());
        ProcessingContext context = StubProcessingContext.forMessage(event);
        when(mockSagaRepository.createInstance(any(), any())).thenReturn(mockSaga1);
        when(mockSagaRepository.find(any())).thenReturn(Collections.emptySet());

        testSubject.handle(event, context, Segment.ROOT_SEGMENT);
        verify(mockSagaRepository).createInstance(any(), any());
    }

    @Test
    void sagaIsOnlyCreatedInSegmentMatchingAssociationValue() throws Exception {
        testSubject = TestableAbstractSagaManager.builder()
                                                 .sagaRepository(mockSagaRepository)
                                                 .listenerInvocationErrorHandler(mockErrorHandler)
                                                 .sagaCreationPolicy(SagaCreationPolicy.IF_NONE_FOUND)
                                                 .associationValue(new AssociationValue("someKey", "someValue"))
                                                 .build();

        Segment[] segments = Segment.ROOT_SEGMENT.split();
        Segment matchingSegment = segments[0].matches("someValue") ? segments[0] : segments[1];
        Segment otherSegment = segments[0].matches("someValue") ? segments[1] : segments[0];

        EventMessage event = new GenericEventMessage(new MessageType("event"), new Object());
        ProcessingContext context = StubProcessingContext.forMessage(event);
        ArgumentCaptor<String> createdSaga = ArgumentCaptor.forClass(String.class);
        when(mockSagaRepository.createInstance(createdSaga.capture(), any())).thenReturn(mockSaga1);
        when(mockSagaRepository.find(any())).thenReturn(Collections.emptySet());

        testSubject.handle(event, context, otherSegment);
        verify(mockSagaRepository, never()).createInstance(any(), any());

        testSubject.handle(event, context, matchingSegment);
        verify(mockSagaRepository).createInstance(any(), any());

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
    void sagaIsNotCreatedIfAssociationValueAndSagaIdMatchDifferentSegments() throws Exception {
        AssociationValue associationValue = new AssociationValue("someKey", "someValue");
        testSubject = TestableAbstractSagaManager.builder()
                                                 .sagaRepository(mockSagaRepository)
                                                 .listenerInvocationErrorHandler(mockErrorHandler)
                                                 .sagaCreationPolicy(SagaCreationPolicy.IF_NONE_FOUND)
                                                 .associationValue(associationValue)
                                                 .build();

        // Test won't work if the saga ID and association value map to the same minimum-sized segment.
        assumeTrue((associationValue.hashCode() & Integer.MAX_VALUE) !=
                           (mockSaga1.getSagaIdentifier().hashCode() & Integer.MAX_VALUE));

        EventMessage event = new GenericEventMessage(new MessageType("event"), new Object());
        ProcessingContext context = StubProcessingContext.forMessage(event);

        String sagaId = mockSaga1.getSagaIdentifier();
        when(mockSagaRepository.find(any())).thenReturn(singleton(sagaId));
        when(mockSagaRepository.createInstance(any(), any())).thenReturn(mockSaga2);

        Segment matchesIdSegment = Segment.ROOT_SEGMENT;
        Segment matchesValueSegment;
        do {
            Segment[] segments = matchesIdSegment.split();
            matchesIdSegment = segments[0].matches(sagaId) ? segments[0] : segments[1];
            matchesValueSegment = segments[0].matches(associationValue) ? segments[0] : segments[1];
        } while (matchesIdSegment.equals(matchesValueSegment));

        testSubject.handle(event, context, matchesIdSegment);
        testSubject.handle(event, StubProcessingContext.forMessage(event), matchesValueSegment);
        verify(mockSagaRepository, never()).createInstance(any(), any());
        verify(mockSaga1).handleSync(event, context);
    }

    @Test
    void exceptionSuppressed() throws Exception {
        EventMessage event = new GenericEventMessage(new MessageType("event"), new Object());
        ProcessingContext context = StubProcessingContext.forMessage(event);
        MockException toBeThrown = new MockException();
        doThrow(toBeThrown).when(mockSaga1).handleSync(event, context);
        testSubject.handle(event, context, Segment.ROOT_SEGMENT);
        verify(mockSaga1).handleSync(event, context);
        verify(mockSaga2).handleSync(event, context);
        verify(mockSaga3, never()).handleSync(event, context);
        verify(mockErrorHandler).onError(toBeThrown, event, mockSaga1);
    }

    @SuppressWarnings({"unchecked"})
    private <T> Set<T> setOf(T... items) {
        return new CopyOnWriteArraySet<>(Arrays.asList(items));
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
        public boolean canHandle(@NonNull EventMessage eventMessage, @NonNull ProcessingContext context,
                                 @NonNull Segment segment) {
            return true;
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

            @Override
            public Builder listenerInvocationErrorHandler(
                    ListenerInvocationErrorHandler listenerInvocationErrorHandler) {
                super.listenerInvocationErrorHandler(listenerInvocationErrorHandler);
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

            public Builder spanFactory(SagaManagerSpanFactory spanFactory) {
                super.spanFactory(spanFactory);
                return this;
            }

            public TestableAbstractSagaManager build() {
                return new TestableAbstractSagaManager(this);
            }
        }
    }
}
