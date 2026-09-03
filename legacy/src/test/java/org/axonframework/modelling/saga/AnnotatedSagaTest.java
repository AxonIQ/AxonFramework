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
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.Metadata;
import org.axonframework.messaging.core.annotation.MessageHandlingMember;
import org.axonframework.messaging.core.interception.annotation.NoMoreInterceptors;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.StubProcessingContext;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.GenericEventMessage;
import org.axonframework.messaging.eventhandling.replay.ResetContext;
import org.axonframework.messaging.eventhandling.replay.ResetNotSupportedException;
import org.axonframework.modelling.saga.metamodel.AnnotationSagaMetaModelFactory;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.*;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * Test class validating the {@link AnnotatedSaga}, in particular its role as the {@link SagaLifecycle} for its wrapped
 * saga instance.
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

    @Nested
    class EventHandling {

        @Test
        void invokesTheHandlerMatchingTheAssociationValue() {
            // given
            testSubject.associateWith(new AssociationValue("propertyName", "id"));

            // when
            var matchingEvent = new GenericEventMessage(new MessageType("event"), new RegularEvent("id"));
            testSubject.handle(matchingEvent, StubProcessingContext.forMessage(matchingEvent))
                       .asCompletableFuture()
                       .orTimeout(50, TimeUnit.MILLISECONDS)
                       .join();
            var nonMatchingEvent = new GenericEventMessage(new MessageType("event"), new RegularEvent("wrongId"));
            testSubject.handle(nonMatchingEvent, StubProcessingContext.forMessage(nonMatchingEvent))
                       .asCompletableFuture()
                       .orTimeout(50, TimeUnit.MILLISECONDS)
                       .join();
            var unhandledEvent = new GenericEventMessage(new MessageType("event"), new Object());
            testSubject.handle(unhandledEvent, StubProcessingContext.forMessage(unhandledEvent))
                       .asCompletableFuture()
                       .orTimeout(50, TimeUnit.MILLISECONDS)
                       .join();

            // then
            assertThat(testSaga.invocationCount).isEqualTo(1);
        }

        @Test
        void resolvesTheAssociationValueFromMetadataWhenConfigured() {
            // given
            testSubject.associateWith(new AssociationValue("propertyName", "id"));
            Map<String, String> metadata = new HashMap<>();
            metadata.put("propertyName", "id");

            // when
            EventMessage eventWithMetadata = new GenericEventMessage(
                    new MessageType("event"), new EventWithoutProperties(), new Metadata(metadata)
            );
            testSubject.handle(eventWithMetadata, StubProcessingContext.forMessage(eventWithMetadata))
                       .asCompletableFuture()
                       .orTimeout(50, TimeUnit.MILLISECONDS)
                       .join();
            EventMessage eventWithoutMetadata =
                    new GenericEventMessage(new MessageType("event"), new EventWithoutProperties());
            testSubject.handle(eventWithoutMetadata, StubProcessingContext.forMessage(eventWithoutMetadata))
                       .asCompletableFuture()
                       .orTimeout(50, TimeUnit.MILLISECONDS)
                       .join();

            // then
            assertThat(testSaga.invocationCount).isEqualTo(1);
        }

        @Test
        void endsTheSagaWhenAnEndSagaAnnotatedHandlerIsInvoked() {
            // given
            testSubject.associateWith(new AssociationValue("propertyName", "id"));

            // when
            var event1 = new GenericEventMessage(new MessageType("event"), new RegularEvent("id"));
            testSubject.handle(event1, StubProcessingContext.forMessage(event1))
                       .asCompletableFuture()
                       .orTimeout(50, TimeUnit.MILLISECONDS)
                       .join();
            var event2 = new GenericEventMessage(new MessageType("event"), new Object());
            testSubject.handle(event2, StubProcessingContext.forMessage(event2))
                       .asCompletableFuture()
                       .orTimeout(50, TimeUnit.MILLISECONDS)
                       .join();
            var event3 = new GenericEventMessage(new MessageType("event"), new SagaEndEvent("id"));
            testSubject.handle(event3, StubProcessingContext.forMessage(event3))
                       .asCompletableFuture()
                       .orTimeout(50, TimeUnit.MILLISECONDS)
                       .join();

            // then
            assertThat(testSaga.invocationCount).isEqualTo(2);
            assertThat(testSubject.isActive()).isFalse();
        }

        @Test
        void endsTheSagaWhenAnEndSagaAnnotatedHandlerRemovesTheLastAssociationExplicitly() {
            // given
            StubAnnotatedSagaWithExplicitAssociationRemoval explicitRemovalSaga =
                    new StubAnnotatedSagaWithExplicitAssociationRemoval();
            AnnotatedSaga<StubAnnotatedSagaWithExplicitAssociationRemoval> explicitRemovalSubject = new AnnotatedSaga<>(
                    "id", Collections.emptySet(), explicitRemovalSaga,
                    new AnnotationSagaMetaModelFactory().modelOf(StubAnnotatedSagaWithExplicitAssociationRemoval.class),
                    NoMoreInterceptors.instance()
            );
            explicitRemovalSubject.associateWith(new AssociationValue("propertyName", "id"));

            // when
            var event1 = new GenericEventMessage(new MessageType("event"), new RegularEvent("id"));
            explicitRemovalSubject.handle(event1, StubProcessingContext.forMessage(event1))
                                  .asCompletableFuture()
                                  .orTimeout(50, TimeUnit.MILLISECONDS)
                                  .join();
            var event2 = new GenericEventMessage(new MessageType("event"), new SagaEndEvent("id"));
            explicitRemovalSubject.handle(event2, StubProcessingContext.forMessage(event2))
                                  .asCompletableFuture()
                                  .orTimeout(50, TimeUnit.MILLISECONDS)
                                  .join();

            // then
            assertThat(explicitRemovalSaga.invocationCount).isEqualTo(2);
            assertThat(explicitRemovalSubject.isActive()).isFalse();
            assertThat(explicitRemovalSubject.associationValues()).isEmpty();
        }

        @Test
        void invokesTheHandlerMatchingTheAssociationValueUsingUniformAccessPrinciple() {
            // given
            testSubject.associateWith(new AssociationValue("propertyName", "id"));

            // when
            var event1 = new GenericEventMessage(new MessageType("event"), new UniformAccessEvent("id"));
            testSubject.handle(event1, StubProcessingContext.forMessage(event1))
                       .asCompletableFuture()
                       .orTimeout(50, TimeUnit.MILLISECONDS)
                       .join();
            var event2 = new GenericEventMessage(new MessageType("event"), new Object());
            testSubject.handle(event2, StubProcessingContext.forMessage(event2))
                       .asCompletableFuture()
                       .orTimeout(50, TimeUnit.MILLISECONDS)
                       .join();
            var event3 = new GenericEventMessage(new MessageType("event"), new SagaEndEvent("id"));
            testSubject.handle(event3, StubProcessingContext.forMessage(event3))
                       .asCompletableFuture()
                       .orTimeout(50, TimeUnit.MILLISECONDS)
                       .join();

            // then
            assertThat(testSaga.invocationCount).isEqualTo(2);
            assertThat(testSubject.isActive()).isFalse();
        }
    }

    @Nested
    class MetaModelValidation {

        @Test
        void rejectsAnAssociationPropertyThatDoesNotExistOnThePayload() {
            AnnotationSagaMetaModelFactory metaModelFactory = new AnnotationSagaMetaModelFactory();

            assertThatThrownBy(() -> metaModelFactory.modelOf(SagaAssociationPropertyNotExistingInPayload.class))
                    .isInstanceOf(AxonConfigurationException.class);
        }

        @Test
        void rejectsAnEmptyAssociationProperty() {
            AnnotationSagaMetaModelFactory metaModelFactory = new AnnotationSagaMetaModelFactory();

            assertThatThrownBy(() -> metaModelFactory.modelOf(SagaAssociationPropertyEmpty.class))
                    .isInstanceOf(AxonConfigurationException.class);
        }

        @Test
        void rejectsAnAssociationResolverWithoutANoArgConstructor() {
            AnnotationSagaMetaModelFactory metaModelFactory = new AnnotationSagaMetaModelFactory();

            assertThatThrownBy(() -> metaModelFactory.modelOf(SagaUsingResolverWithoutNoArgConstructor.class))
                    .isInstanceOf(AxonConfigurationException.class);
        }
    }

    @Nested
    class Reset {

        @Test
        void prepareResetDelegatesToPrepareResetWithNullResetContextAndThrowsResetNotSupportedException() {
            AnnotatedSaga<StubAnnotatedSaga> spiedTestSubject = spy(testSubject);

            assertThatThrownBy(() -> spiedTestSubject.handle((ResetContext) null, null))
                    .isInstanceOf(ResetNotSupportedException.class);
            verify(spiedTestSubject).handle((ResetContext) null, null);
        }
    }

    @Nested
    class Associations {

        @Test
        void associationValuesReflectsAssociateAndRemoveAssociationWithImmediately() {
            // given / when
            testSubject.associateWith(new AssociationValue("propertyName", "id"));
            Set<AssociationValue> afterFirstAssociation = testSubject.associationValues();

            // then
            assertThat(afterFirstAssociation).containsExactly(new AssociationValue("propertyName", "id"));

            // when
            testSubject.associateWith(new AssociationValue("someOtherProperty", "3"));
            Set<AssociationValue> afterSecondAssociation = testSubject.associationValues();

            // then
            assertThat(afterSecondAssociation).containsExactlyInAnyOrder(
                    new AssociationValue("propertyName", "id"),
                    new AssociationValue("someOtherProperty", "3")
            );
        }
    }

    @Nested
    class SagaLifecycleScoping {

        @Test
        void eachSagaHandlingTheSameEventInTheSameProcessingContextResolvesItsOwnSagaLifecycle() {
            // given
            var metaModel = new AnnotationSagaMetaModelFactory().modelOf(LifecycleCapturingSaga.class);
            LifecycleCapturingSaga saga1 = new LifecycleCapturingSaga();
            LifecycleCapturingSaga saga2 = new LifecycleCapturingSaga();
            AnnotatedSaga<LifecycleCapturingSaga> subject1 =
                    new AnnotatedSaga<>("id1", Collections.emptySet(), saga1, metaModel, NoMoreInterceptors.instance());
            AnnotatedSaga<LifecycleCapturingSaga> subject2 =
                    new AnnotatedSaga<>("id2", Collections.emptySet(), saga2, metaModel, NoMoreInterceptors.instance());
            subject1.associateWith(new AssociationValue("propertyName", "id"));
            subject2.associateWith(new AssociationValue("propertyName", "id"));

            // when - both sagas handle the same event through the same ProcessingContext, as
            // AbstractSagaManager does when multiple sagas are associated with one event
            var event = new GenericEventMessage(new MessageType("event"), new RegularEvent("id"));
            ProcessingContext sharedContext = StubProcessingContext.forMessage(event);
            subject1.handle(event, sharedContext).asCompletableFuture()
                    .orTimeout(50, TimeUnit.MILLISECONDS)
                    .join();
            subject2.handle(event, sharedContext).asCompletableFuture()
                    .orTimeout(50, TimeUnit.MILLISECONDS)
                    .join();

            // then - each saga resolved a SagaLifecycle parameter bound to itself, not to the other saga
            assertThat(saga1.capturedLifecycle).isSameAs(subject1);
            assertThat(saga2.capturedLifecycle).isSameAs(subject2);
            assertThat(saga1.capturedLifecycle).isNotSameAs(saga2.capturedLifecycle);
        }
    }

    /**
     * Axon Framework 4 sagas were synchronous by construction: {@code EventMessageHandler#handleSync} returned the
     * handler's value, which the framework ignored, so an asynchronous result was dropped and never took part in the
     * transaction. {@link org.axonframework.messaging.eventhandling.EventHandlingComponent} can express one, and the
     * unit of work awaits it, which would silently move a saga's store write off the transaction's thread. Rejecting
     * it keeps the Axon Framework 4 contract.
     */
    @Nested
    class SynchronousHandling {

        @Test
        void aHandlerReturningAnAlreadyCompletedResultIsAccepted() {
            // given a saga whose handler returns a future that is already done
            AsynchronousSaga saga = new AsynchronousSaga(CompletableFuture.completedFuture(null));
            AnnotatedSaga<AsynchronousSaga> subject = asynchronousSagaSubject(saga);

            // when
            var event = new GenericEventMessage(new MessageType("event"), new RegularEvent("id"));
            subject.handle(event, StubProcessingContext.forMessage(event))
                   .asCompletableFuture()
                   .orTimeout(50, TimeUnit.MILLISECONDS)
                   .join();

            // then it is handled like any synchronous handler
            assertThat(saga.invoked).isTrue();
        }

        @Test
        void aVoidHandlerIsAccepted() {
            // given the ordinary case / when
            testSubject.associateWith(new AssociationValue("propertyName", "id"));
            var event = new GenericEventMessage(new MessageType("event"), new RegularEvent("id"));
            testSubject.handle(event, StubProcessingContext.forMessage(event))
                       .asCompletableFuture()
                       .orTimeout(50, TimeUnit.MILLISECONDS)
                       .join();

            // then
            assertThat(testSaga.invocationCount).isEqualTo(1);
        }

        @Test
        void aHandlerWhoseResultIsStillPendingIsRejected() {
            // given a saga whose handler returns a future that nothing has completed yet
            CompletableFuture<Object> pending = new CompletableFuture<>();
            AsynchronousSaga saga = new AsynchronousSaga(pending);
            AnnotatedSaga<AsynchronousSaga> subject = asynchronousSagaSubject(saga);

            // when
            var event = new GenericEventMessage(new MessageType("event"), new RegularEvent("id"));
            var result = subject.handle(event, StubProcessingContext.forMessage(event));

            // then handling fails immediately rather than being awaited, because whatever the handler is doing is no
            // longer on the thread that owns the transaction
            assertThatThrownBy(() -> result.asCompletableFuture().orTimeout(50, TimeUnit.MILLISECONDS).join())
                    .hasCauseInstanceOf(SagaExecutionException.class)
                    .hasMessageContaining("must complete");
            pending.complete(null);
        }

        private AnnotatedSaga<AsynchronousSaga> asynchronousSagaSubject(AsynchronousSaga saga) {
            AnnotatedSaga<AsynchronousSaga> subject = new AnnotatedSaga<>(
                    "id", Collections.emptySet(), saga,
                    new AnnotationSagaMetaModelFactory().modelOf(AsynchronousSaga.class),
                    NoMoreInterceptors.instance()
            );
            subject.associateWith(new AssociationValue("propertyName", "id"));
            return subject;
        }
    }

    @SuppressWarnings("unused")
    private static class AsynchronousSaga {

        private final CompletableFuture<Object> result;
        private boolean invoked;

        private AsynchronousSaga(CompletableFuture<Object> result) {
            this.result = result;
        }

        @SagaEventHandler(associationProperty = "propertyName")
        public CompletableFuture<Object> handle(RegularEvent event) {
            invoked = true;
            return result;
        }
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

    @SuppressWarnings("unused")
    private static class StubAnnotatedSagaWithExplicitAssociationRemoval {

        private int invocationCount = 0;

        @SagaEventHandler(associationProperty = "propertyName")
        public void handleStubDomainEvent(RegularEvent event) {
            invocationCount++;
        }

        @EndSaga
        @SagaEventHandler(associationProperty = "propertyName")
        public void handleStubDomainEvent(SagaEndEvent event, SagaLifecycle lifecycle) {
            invocationCount++;
            // Demonstrates the migration path called out by SagaLifecycle: a handler that used to call the static
            // SagaLifecycle.removeAssociationWith(...) now declares a SagaLifecycle parameter instead.
            lifecycle.removeAssociationWith("propertyName", event.getPropertyName());
        }
    }

    @SuppressWarnings("unused")
    private static class LifecycleCapturingSaga {

        private SagaLifecycle capturedLifecycle;

        @SagaEventHandler(associationProperty = "propertyName")
        public void handleStubDomainEvent(RegularEvent event, SagaLifecycle lifecycle) {
            this.capturedLifecycle = lifecycle;
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
