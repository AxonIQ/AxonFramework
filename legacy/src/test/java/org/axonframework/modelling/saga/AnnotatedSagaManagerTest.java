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

import org.axonframework.common.FutureUtils;
import org.axonframework.messaging.core.EmptyApplicationContext;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.annotation.MetadataValue;
import org.axonframework.messaging.core.interception.annotation.ExceptionHandler;
import org.axonframework.messaging.core.unitofwork.SimpleUnitOfWorkFactory;
import org.axonframework.messaging.core.unitofwork.StubProcessingContext;
import org.axonframework.messaging.core.unitofwork.UnitOfWork;
import org.axonframework.messaging.core.unitofwork.UnitOfWorkFactory;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.GenericEventMessage;
import org.axonframework.messaging.eventhandling.replay.GenericResetContext;
import org.axonframework.messaging.eventhandling.replay.ResetNotSupportedException;
import org.axonframework.modelling.saga.repository.AnnotatedSagaRepository;
import org.axonframework.modelling.saga.repository.SagaStore;
import org.axonframework.modelling.saga.repository.inmemory.InMemorySagaStore;
import org.junit.jupiter.api.*;

import java.time.Duration;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Collectors;

import static java.util.Collections.singletonMap;
import static org.axonframework.messaging.eventhandling.EventTestUtils.asEventMessage;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Test class validating the {@link AnnotatedSagaManager}.
 *
 * @author Allard Buijze
 */
public class AnnotatedSagaManagerTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private AnnotatedSagaRepository<MyTestSaga> sagaRepository;
    private InMemorySagaStore sagaStore;
    private UnitOfWorkFactory unitOfWorkFactory;

    private AnnotatedSagaManager<MyTestSaga> testSubject;

    @BeforeEach
    void setUp() {
        sagaStore = new InMemorySagaStore();
        sagaRepository = spy(
                AnnotatedSagaRepository.<MyTestSaga>builder()
                                       .sagaType(MyTestSaga.class)
                                       .sagaStore(sagaStore)
                                       .build()
        );
        unitOfWorkFactory = new SimpleUnitOfWorkFactory(EmptyApplicationContext.INSTANCE);
        testSubject = AnnotatedSagaManager.<MyTestSaga>builder()
                                          .sagaRepository(sagaRepository)
                                          .sagaType(MyTestSaga.class)
                                          .sagaFactory(MyTestSaga::new)
                                          .build();
    }

    @Test
    void creationPolicy_NoneExists() throws Exception {
        handle(new GenericEventMessage(new MessageType("event"), new StartingEvent("123")));
        assertEquals(1, repositoryContents("123").size());
    }

    @Test
    void creationPolicy_OneAlreadyExists() throws Exception {
        handle(new GenericEventMessage(new MessageType("event"), new StartingEvent("123")));
        handle(new GenericEventMessage(new MessageType("event"), new StartingEvent("123")));
        assertEquals(1, repositoryContents("123").size());
    }

    @Test
    void handleUnrelatedEvent() throws Exception {
        handle(new GenericEventMessage(new MessageType("event"), "Unrelated"));
        verify(sagaRepository, never()).find(isNull(), any());
    }

    @Test
    void creationPolicy_CreationForced() throws Exception {
        StartingEvent startingEvent = new StartingEvent("123");
        handle(new GenericEventMessage(new MessageType("event"), startingEvent));
        handle(new GenericEventMessage(new MessageType("event"), new ForcingStartEvent("123")));
        Collection<MyTestSaga> sagas = repositoryContents("123");
        assertEquals(2, sagas.size());
        for (MyTestSaga saga : sagas) {
            if (saga.getCapturedEvents().contains(startingEvent)) {
                assertEquals(2, saga.getCapturedEvents().size());
            }
            assertFalse(saga.getCapturedEvents().isEmpty());
        }
    }

    @Test
    void creationPolicy_SagaNotCreated() throws Exception {
        handle(new GenericEventMessage(new MessageType("event"), new MiddleEvent("123")));
        assertEquals(0, repositoryContents("123").size());
    }

    @Test
    void mostSpecificHandlerEvaluatedFirst() throws Exception {
        handle(new GenericEventMessage(new MessageType("event"), new StartingEvent("12")));
        handle(new GenericEventMessage(new MessageType("event"), new StartingEvent("23")));
        assertEquals(1, repositoryContents("12").size());
        assertEquals(1, repositoryContents("23").size());

        handle(new GenericEventMessage(new MessageType("event"), new MiddleEvent("12")));
        handle(new GenericEventMessage(
                new MessageType("event"), new MiddleEvent("23"), singletonMap("catA", "value")
        ));
        assertEquals(0, repositoryContents("12").iterator().next().getSpecificHandlerInvocations());
        assertEquals(1, repositoryContents("23").iterator().next().getSpecificHandlerInvocations());
    }

    @Test
    void nullAssociationValueIsIgnored() throws Exception {
        handle(new GenericEventMessage(new MessageType("event"), new StartingEvent(null)));

        verify(sagaRepository, never()).find(isNull(), any());
    }

    @Test
    void lifecycle_DestroyedOnEnd() throws Exception {
        handle(new GenericEventMessage(new MessageType("event"), new StartingEvent("12")));
        handle(new GenericEventMessage(new MessageType("event"), new StartingEvent("23")));
        handle(new GenericEventMessage(new MessageType("event"), new MiddleEvent("12")));
        handle(new GenericEventMessage(
                new MessageType("event"), new MiddleEvent("23"), singletonMap("catA", "value")
        ));

        assertEquals(1, repositoryContents("12").size());
        assertEquals(1, repositoryContents("23").size());
        assertEquals(0, repositoryContents("12").iterator().next().getSpecificHandlerInvocations());
        assertEquals(1, repositoryContents("23").iterator().next().getSpecificHandlerInvocations());
        handle(new GenericEventMessage(new MessageType("event"), new EndingEvent("12")));
        assertEquals(1, repositoryContents("23").size());
        assertEquals(0, repositoryContents("12").size());
        handle(new GenericEventMessage(new MessageType("event"), new EndingEvent("23")));
        assertEquals(0, repositoryContents("23").size());
        assertEquals(0, repositoryContents("12").size());
    }

    @Test
    void nullAssociationValueDoesNotThrowNullPointer() throws Exception {
        handle(asEventMessage(new StartingEvent(null)));
    }

    @Test
    void lifeCycle_ExistingInstanceIgnoresEvent() throws Exception {
        handle(new GenericEventMessage(new MessageType("event"), new StartingEvent("12")));
        handle(new GenericEventMessage(new MessageType("event"), new UnrelatedDomainEvent()));
        assertEquals(1, repositoryContents("12").size());
        assertEquals(1, repositoryContents("12").iterator().next().getCapturedEvents().size());
    }

    @Test
    void lifeCycle_IgnoredEventDoesNotCreateInstance() throws Exception {
        handle(new GenericEventMessage(new MessageType("event"), new UnrelatedDomainEvent()));
        assertEquals(0, repositoryContents("12").size());
    }

    @Test
    void doesNotSupportReset() {
        assertFalse(testSubject.supportsReset());
    }

    @Test
    void handlingAResetContextThrowsResetNotSupportedException() {
        var resetContext = new GenericResetContext(new MessageType(String.class), "reset-info");

        assertThrows(
                ResetNotSupportedException.class,
                () -> testSubject.handle(resetContext, StubProcessingContext.forMessage(resetContext))
        );
    }

    /**
     * A {@link SagaLifecycle} parameter is how an Axon Framework 4 saga's calls to the static
     * {@code SagaLifecycle.associateWith(...)} are expressed here, so a saga declaring one has to be as visible to the
     * manager as any other. The manager resolves handlers to extract association values and a creation policy before
     * any saga exists, which is where a resolver reporting no match would hide the handler completely.
     */
    @Nested
    class SagaLifecycleInjection {

        private AnnotatedSagaManager<LifecycleInjectingTestSaga> lifecycleTestSubject;

        @BeforeEach
        void setUp() {
            lifecycleTestSubject =
                    AnnotatedSagaManager.<LifecycleInjectingTestSaga>builder()
                                        .sagaRepository(AnnotatedSagaRepository.<LifecycleInjectingTestSaga>builder()
                                                                              .sagaType(LifecycleInjectingTestSaga.class)
                                                                              .sagaStore(sagaStore)
                                                                              .build())
                                        .sagaType(LifecycleInjectingTestSaga.class)
                                        .sagaFactory(LifecycleInjectingTestSaga::new)
                                        .build();
        }

        @Test
        void aSagaWhoseStartingHandlerDeclaresASagaLifecycleIsStarted() {
            handle(new GenericEventMessage(new MessageType("event"), new StartingEvent("123")));

            assertEquals(1, sagaStore.findSagas(LifecycleInjectingTestSaga.class,
                                                new AssociationValue("myIdentifier", "123")).size());
        }

        @Test
        void theAssociationTheHandlerAddedThroughTheLifecycleIsStored() {
            handle(new GenericEventMessage(new MessageType("event"), new StartingEvent("123")));

            assertEquals(1, sagaStore.findSagas(LifecycleInjectingTestSaga.class,
                                                new AssociationValue("secondaryIdentifier", "secondary-123")).size());
        }

        @Test
        void theSagaIsFoundAgainByTheAssociationItAddedItself() {
            handle(new GenericEventMessage(new MessageType("event"), new StartingEvent("123")));

            // a second starting event for the same identifier must not create a second saga, which it only avoids if
            // the manager could see the handler and find the existing saga through it
            handle(new GenericEventMessage(new MessageType("event"), new StartingEvent("123")));

            assertEquals(1, sagaStore.findSagas(LifecycleInjectingTestSaga.class,
                                                new AssociationValue("myIdentifier", "123")).size());
        }

        private void handle(EventMessage event) {
            UnitOfWork unitOfWork = unitOfWorkFactory.create();
            unitOfWork.runOnInvocation(context -> lifecycleTestSubject.handle(event, context));
            FutureUtils.joinAndUnwrap(unitOfWork.execute(), TIMEOUT);
        }
    }

    /**
     * A saga that suppresses its own handler failure through an {@link ExceptionHandler} reaches the outcome the Axon
     * Framework 4 {@code ListenerInvocationErrorHandler} default produced: the unit of work commits, the saga is
     * stored, and the saga counts as having taken the event so {@link SagaCreationPolicy#IF_NONE_FOUND} does not start
     * a second one.
     */
    @Nested
    class SuppressedHandlerFailures {

        private AnnotatedSagaManager<SuppressingTestSaga> suppressingTestSubject;

        @BeforeEach
        void setUp() {
            suppressingTestSubject =
                    AnnotatedSagaManager.<SuppressingTestSaga>builder()
                                        .sagaRepository(AnnotatedSagaRepository.<SuppressingTestSaga>builder()
                                                                               .sagaType(SuppressingTestSaga.class)
                                                                               .sagaStore(sagaStore)
                                                                               .build())
                                        .sagaType(SuppressingTestSaga.class)
                                        .sagaFactory(SuppressingTestSaga::new)
                                        .build();
        }

        @Test
        void aSuppressedFailureStillStoresTheSaga() {
            handle(new GenericEventMessage(new MessageType("event"), new StartingEvent("123")));

            Collection<SuppressingTestSaga> sagas = suppressingRepositoryContents();
            assertEquals(1, sagas.size());
            SuppressingTestSaga saga = sagas.iterator().next();
            assertEquals(1, saga.getHandlerInvocations());
            assertEquals(1, saga.getSuppressedFailures());
        }

        @Test
        void aSuppressedFailureStillCountsAsInvoked() {
            handle(new GenericEventMessage(new MessageType("event"), new StartingEvent("123")));
            handle(new GenericEventMessage(new MessageType("event"), new StartingEvent("123")));

            Collection<SuppressingTestSaga> sagas = suppressingRepositoryContents();
            assertEquals(1, sagas.size());
            assertEquals(2, sagas.iterator().next().getHandlerInvocations());
        }

        private void handle(EventMessage event) {
            UnitOfWork unitOfWork = unitOfWorkFactory.create();
            unitOfWork.runOnInvocation(context -> suppressingTestSubject.handle(event, context));
            FutureUtils.joinAndUnwrap(unitOfWork.execute(), TIMEOUT);
        }

        private Collection<SuppressingTestSaga> suppressingRepositoryContents() {
            return sagaStore.findSagas(SuppressingTestSaga.class, new AssociationValue("myIdentifier", "123"))
                            .stream()
                            .map(id -> sagaStore.loadSaga(SuppressingTestSaga.class, id))
                            .map(SagaStore.Entry::saga)
                            .collect(Collectors.toList());
        }
    }

    private void handle(EventMessage event) {
        UnitOfWork unitOfWork = unitOfWorkFactory.create();
        unitOfWork.runOnInvocation(context -> testSubject.handle(event, context));
        FutureUtils.joinAndUnwrap(unitOfWork.execute(), TIMEOUT);
    }

    private Collection<MyTestSaga> repositoryContents(String lookupValue) {
        return sagaStore.findSagas(MyTestSaga.class, new AssociationValue("myIdentifier", lookupValue))
                        .stream()
                        .map(id -> sagaStore.loadSaga(MyTestSaga.class, id))
                        .map(SagaStore.Entry::saga)
                        .collect(Collectors.toList());
    }

    @SuppressWarnings("unused")
    public static class MyTestSaga {

        private final List<Object> capturedEvents = new LinkedList<>();
        private int specificHandlerInvocations = 0;

        @CustomStartingSagaEventHandler
        public void handleSomeEvent(StartingEvent event) {
            capturedEvents.add(event);
        }

        @StartSaga
        @SagaEventHandler(associationProperty = "myIdentifier")
        public void handleSomeEvent(SlowStartingEvent event) throws InterruptedException {
            event.getStartCdl().countDown();
            capturedEvents.add(event);
            Thread.sleep(event.getDuration());
        }

        @StartSaga(forceNew = true)
        @SagaEventHandler(associationProperty = "myIdentifier")
        public void handleSomeEvent(ForcingStartEvent event) {
            capturedEvents.add(event);
        }

        @CustomEndingSagaEventHandler
        public void handleSomeEvent(EndingEvent event) {
            capturedEvents.add(event);
        }

        @SagaEventHandler(associationProperty = "myIdentifier")
        public void handleMiddleEvent(MiddleEvent event) {
            capturedEvents.add(event);
        }

        @SagaEventHandler(associationProperty = "myIdentifier")
        public void handleSpecificMiddleEvent(MiddleEvent event,
                                              @MetadataValue(value = "catA", required = true) String category) {
            // this handler is more specific, but requires metadata that not all events might have
            capturedEvents.add(event);
            specificHandlerInvocations++;
        }

        public List<Object> getCapturedEvents() {
            return capturedEvents;
        }

        public int getSpecificHandlerInvocations() {
            return specificHandlerInvocations;
        }
    }

    @SuppressWarnings("unused")
    public static abstract class MyIdentifierEvent {

        private final String myIdentifier;

        public MyIdentifierEvent(String myIdentifier) {
            this.myIdentifier = myIdentifier;
        }

        public String getMyIdentifier() {
            return myIdentifier;
        }
    }

    public static class StartingEvent extends MyIdentifierEvent {

        public StartingEvent(String myIdentifier) {
            super(myIdentifier);
        }
    }

    public static class SlowStartingEvent extends StartingEvent {

        private final CountDownLatch startCdl;
        private final long duration;

        protected SlowStartingEvent(String myIdentifier, CountDownLatch startCdl, long duration) {
            super(myIdentifier);
            this.startCdl = startCdl;
            this.duration = duration;
        }

        public long getDuration() {
            return duration;
        }

        public CountDownLatch getStartCdl() {
            return startCdl;
        }
    }

    public static class ForcingStartEvent extends MyIdentifierEvent {

        public ForcingStartEvent(String myIdentifier) {
            super(myIdentifier);
        }
    }

    public static class EndingEvent extends MyIdentifierEvent {

        public EndingEvent(String myIdentifier) {
            super(myIdentifier);
        }
    }

    public static class MiddleEvent extends MyIdentifierEvent {

        public MiddleEvent(String myIdentifier) {
            super(myIdentifier);
        }
    }

    public static class UnrelatedDomainEvent {

    }

    @SuppressWarnings("unused")
    public static class LifecycleInjectingTestSaga {

        @StartSaga
        @SagaEventHandler(associationProperty = "myIdentifier")
        public void handleStartingEvent(StartingEvent event, SagaLifecycle lifecycle) {
            lifecycle.associateWith("secondaryIdentifier", "secondary-" + event.getMyIdentifier());
        }
    }

    @SuppressWarnings("unused")
    public static class SuppressingTestSaga {

        private int handlerInvocations = 0;
        private int suppressedFailures = 0;

        @StartSaga
        @SagaEventHandler(associationProperty = "myIdentifier")
        public void handleStartingEvent(StartingEvent event) {
            handlerInvocations++;
            throw new IllegalStateException("saga handler failed");
        }

        @ExceptionHandler
        public void on(IllegalStateException failure) {
            suppressedFailures++;
        }

        public int getHandlerInvocations() {
            return handlerInvocations;
        }

        public int getSuppressedFailures() {
            return suppressedFailures;
        }
    }
}
