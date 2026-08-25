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

import org.axonframework.common.AxonConfigurationException;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.GenericEventMessage;
import org.axonframework.messaging.eventhandling.replay.ResetNotSupportedException;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.Metadata;
import org.axonframework.messaging.core.unitofwork.StubProcessingContext;
import org.axonframework.messaging.core.annotation.MessageHandlingMember;
import org.axonframework.messaging.core.interception.annotation.NoMoreInterceptors;
import org.axonframework.modelling.saga.metamodel.AnnotationSagaMetaModelFactory;
import org.junit.jupiter.api.*;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.NonNull;

import static org.axonframework.modelling.saga.SagaLifecycle.removeAssociationWith;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Test class validating the {@link AnnotatedSaga}.
 *
 * @author Allard Buijze
 * @author Sofia Guy Ang
 */
class AnnotatedSagaTest {

    private StubAnnotatedSaga testSaga;
    private AnnotatedSaga<StubAnnotatedSaga> testSubject;

    @BeforeEach
    void setUp() {
        testSaga = new StubAnnotatedSaga();
        testSubject = new AnnotatedSaga<>(
                "id", Collections.emptySet(), testSaga,
                new AnnotationSagaMetaModelFactory().modelOf(StubAnnotatedSaga.class),
                NoMoreInterceptors.instance()
        );
    }

    @Test
    void invokeSaga() {
        testSubject.doAssociateWith(new AssociationValue("propertyName", "id"));

        var event1 = new GenericEventMessage(new MessageType("event"), new RegularEvent("id"));
        testSubject.handleSync(event1, StubProcessingContext.forMessage(event1));

        var event2 = new GenericEventMessage(new MessageType("event"), new RegularEvent("wrongId"));
        testSubject.handleSync(event2, StubProcessingContext.forMessage(event2));

        var event3 = new GenericEventMessage(new MessageType("event"), new Object());
        testSubject.handleSync(event3, StubProcessingContext.forMessage(event3));

        assertEquals(1, testSaga.invocationCount);
    }

    @Test
    void invokeSagaAssociationPropertyNotExistingInPayload() {
        AnnotationSagaMetaModelFactory testSubject = new AnnotationSagaMetaModelFactory();
        assertThrows(
                AxonConfigurationException.class,
                () -> testSubject.modelOf(SagaAssociationPropertyNotExistingInPayload.class)
        );
    }

    @Test
    void invokeSagaAssociationPropertyEmpty() {
        AnnotationSagaMetaModelFactory testSubject = new AnnotationSagaMetaModelFactory();
        assertThrows(AxonConfigurationException.class, () -> testSubject.modelOf(SagaAssociationPropertyEmpty.class));
    }

    @Test
    void invokeSagaMetadataAssociationResolver() {
        testSubject.doAssociateWith(new AssociationValue("propertyName", "id"));
        Map<String, String> metadata = new HashMap<>();
        metadata.put("propertyName", "id");
        EventMessage eventWithMetadata = new GenericEventMessage(
                new MessageType("event"), new EventWithoutProperties(), new Metadata(metadata)
        );
        testSubject.handleSync(eventWithMetadata, StubProcessingContext.forMessage(eventWithMetadata));
        GenericEventMessage eventWithoutMetadata = new GenericEventMessage(new MessageType(
                "event"),
                                                                                                     new EventWithoutProperties());
        testSubject.handleSync(eventWithoutMetadata, StubProcessingContext.forMessage(eventWithoutMetadata));

        assertEquals(1, testSaga.invocationCount);
    }

    @Test
    void invokeSagaResolverWithoutNoArgConstructor() {
        assertThrows(
                AxonConfigurationException.class,
                () -> new AnnotationSagaMetaModelFactory().modelOf(SagaUsingResolverWithoutNoArgConstructor.class)
        );
    }

    @Test
    void endedAfterInvocationBeanProperty() {
        testSubject.doAssociateWith(new AssociationValue("propertyName", "id"));
        var event1 = new GenericEventMessage(new MessageType("event"),
                                               new RegularEvent("id"));
        testSubject.handleSync(event1, StubProcessingContext.forMessage(event1));
        var event2 = new GenericEventMessage(new MessageType("event"), new Object());
        testSubject.handleSync(event2, StubProcessingContext.forMessage(event2));
        var event3 = new GenericEventMessage(new MessageType("event"),
                                               new SagaEndEvent("id"));
        testSubject.handleSync(event3, StubProcessingContext.forMessage(event3));

        assertEquals(2, testSaga.invocationCount);
        assertFalse(testSubject.isActive());
    }

    @Test
    void endedAfterInvocationWhenAssociationIsRemoved() {
        StubAnnotatedSaga testSaga = new StubAnnotatedSagaWithExplicitAssociationRemoval();
        AnnotatedSaga<StubAnnotatedSaga> testSubject = new AnnotatedSaga<>(
                "id", Collections.emptySet(), testSaga,
                new AnnotationSagaMetaModelFactory().modelOf(StubAnnotatedSaga.class),
                NoMoreInterceptors.instance()
        );

        testSubject.doAssociateWith(new AssociationValue("propertyName", "id"));
        var event1 = new GenericEventMessage(new MessageType("event"),
                                               new RegularEvent("id"));
        testSubject.handleSync(event1, StubProcessingContext.forMessage(event1));
        var event2 = new GenericEventMessage(new MessageType("event"), new Object());
        testSubject.handleSync(event2, StubProcessingContext.forMessage(event2));
        var event3 = new GenericEventMessage(new MessageType("event"),
                                               new SagaEndEvent("id"));
        testSubject.handleSync(event3, StubProcessingContext.forMessage(event3));

        assertEquals(2, testSaga.invocationCount);
        assertFalse(testSubject.isActive());
    }

    @Test
    void endedAfterInvocationUniformAccessPrinciple() {
        testSubject.doAssociateWith(new AssociationValue("propertyName", "id"));
        var event1 = new GenericEventMessage(new MessageType("event"), new UniformAccessEvent("id"));
        testSubject.handleSync(event1, StubProcessingContext.forMessage(event1));
        var event2 = new GenericEventMessage(new MessageType("event"), new Object());
        testSubject.handleSync(event2, StubProcessingContext.forMessage(event2));
        var event3 = new GenericEventMessage(new MessageType("event"), new SagaEndEvent("id"));
        testSubject.handleSync(event3, StubProcessingContext.forMessage(event3));

        assertEquals(2, testSaga.invocationCount);
        assertFalse(testSubject.isActive());
    }

    @Test
    void prepareResetThrowsResetNotSupportedException() {
        AnnotatedSaga<StubAnnotatedSaga> spiedTestSubject = spy(testSubject);

        assertThrows(ResetNotSupportedException.class, () -> spiedTestSubject.prepareReset(null));

        verify(spiedTestSubject).prepareReset(null, null);
    }

    @Test
    void prepareResetWithResetContextThrowsResetNotSupportedException() {
        assertThrows(ResetNotSupportedException.class, () -> testSubject.prepareReset("some-reset-context", null));
    }

    @Test
    void lifecycleAssociationValues() {
        testSubject.doAssociateWith(new AssociationValue("propertyName", "id"));
        testSubject.execute(() -> {
            Set<AssociationValue> associationValues = SagaLifecycle.associationValues();
            assertEquals(1, associationValues.size());
            assertTrue(associationValues.contains(new AssociationValue("propertyName", "id")));
        });

        testSubject.doAssociateWith(new AssociationValue("someOtherProperty", "3"));
        testSubject.execute(() -> {
            Set<AssociationValue> associationValues = SagaLifecycle.associationValues();
            assertEquals(2, associationValues.size());
            assertTrue(associationValues.contains(new AssociationValue("propertyName", "id")));
            assertTrue(associationValues.contains(new AssociationValue("someOtherProperty", "3")));
        });
    }

    @SuppressWarnings("unused")
    private static class StubAnnotatedSaga {

        private int invocationCount = 0;

        @SagaEventHandler(associationProperty = "propertyName")
        public void handleStubDomainEvent(RegularEvent event) {
            invocationCount++;
        }

        @SagaEventHandler(associationProperty = "propertyName")
        public void handleStubDomainEvent(UniformAccessEvent event) {
            invocationCount++;
        }

        @SagaEventHandler(associationProperty = "propertyName", associationResolver = MetadataAssociationResolver.class)
        public void handleStubDomainEvent(EventWithoutProperties event) {
            invocationCount++;
        }

        @EndSaga
        @SagaEventHandler(associationProperty = "propertyName")
        public void handleStubDomainEvent(SagaEndEvent event) {
            invocationCount++;
        }
    }

    private static class SagaAssociationPropertyNotExistingInPayload {

        @SuppressWarnings("unused")
        @SagaEventHandler(associationProperty = "propertyName")
        public void handleStubDomainEvent(EventWithoutProperties event) {
        }
    }

    private static class SagaUsingResolverWithoutNoArgConstructor {

        @SuppressWarnings("unused")
        @SagaEventHandler(
                associationProperty = "propertyName",
                associationResolver = OneArgConstructorAssociationResolver.class
        )
        public void handleStubDomainEvent(EventWithoutProperties event) {
        }
    }

    private static class StubAnnotatedSagaWithExplicitAssociationRemoval extends StubAnnotatedSaga {

        @Override
        public void handleStubDomainEvent(SagaEndEvent event) {
            // Since this method overrides a handler, it doesn't need the annotation anymore
            super.handleStubDomainEvent(event);
            removeAssociationWith("propertyName", event.getPropertyName());
        }
    }

    private static class SagaAssociationPropertyEmpty {

        @SuppressWarnings("unused")
        @SagaEventHandler(associationProperty = "")
        public void handleStubDomainEvent(EventWithoutProperties event) {
        }
    }

    private static class RegularEvent {

        private final String propertyName;

        public RegularEvent(String propertyName) {
            this.propertyName = propertyName;
        }

        public String getPropertyName() {
            return propertyName;
        }
    }

    private record UniformAccessEvent(String propertyName) {

    }

    private static class EventWithoutProperties {

    }

    private static class SagaEndEvent extends RegularEvent {

        public SagaEndEvent(String propertyName) {
            super(propertyName);
        }
    }

    private static class OneArgConstructorAssociationResolver implements AssociationResolver {

        String someField;

        public OneArgConstructorAssociationResolver(String someField) {
            this.someField = someField;
        }

        @Override
        public <T> void validate(@NonNull String associationPropertyName, @NonNull MessageHandlingMember<T> handler) {

        }

        @Override
        public <T> Object resolve(@NonNull String associationPropertyName, @NonNull EventMessage message,
                                  @NonNull MessageHandlingMember<T> handler) {
            return null;
        }
    }
}
