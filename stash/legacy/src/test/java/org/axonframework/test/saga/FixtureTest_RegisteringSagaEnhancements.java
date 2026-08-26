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

package org.axonframework.test.saga;

import org.axonframework.messaging.eventhandling.processing.errorhandling.ListenerInvocationErrorHandler;
import org.axonframework.messaging.eventhandling.processing.errorhandling.LoggingErrorHandler;
import org.axonframework.modelling.saga.SagaEventHandler;
import org.axonframework.modelling.saga.StartSaga;
import org.axonframework.test.FixtureExecutionException;
import org.junit.jupiter.api.*;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static java.time.Duration.ofSeconds;
import static java.time.Instant.now;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Test class dedicated to validating custom, saga specific, registered components on {@link SagaTestFixture}.
 *
 * @author Steven van Beelen
 */
class FixtureTest_RegisteringSagaEnhancements {

    private SagaTestFixture<SomeTestSaga> testSubject;

    private AtomicInteger startRecordingCount;

    @BeforeEach
    void setUp() {
        startRecordingCount = new AtomicInteger();

        testSubject = new SagaTestFixture<>(SomeTestSaga.class);
    }

    @Test
    void startRecordingCallbackIsInvokedOnWhenPublishingAnEvent() {
        testSubject.registerStartRecordingCallback(startRecordingCount::getAndIncrement)
                   .givenAPublished(new SomeTestSaga.SomeEvent());
        assertThat(startRecordingCount.get(), equalTo(0));

        testSubject.whenPublishingA(new SomeTestSaga.SomeEvent());
        assertThat(startRecordingCount.get(), equalTo(1));
    }

    @Test
    void startRecordingCallbackIsInvokedOnWhenTimeAdvances() {
        testSubject.registerStartRecordingCallback(startRecordingCount::getAndIncrement)
                   .givenAPublished(new SomeTestSaga.SomeEvent());
        assertThat(startRecordingCount.get(), equalTo(0));

        testSubject.whenTimeAdvancesTo(now());
        assertThat(startRecordingCount.get(), equalTo(1));
    }

    @Test
    void startRecordingCallbackIsInvokedOnWhenTimeElapses() {
        testSubject.registerStartRecordingCallback(startRecordingCount::getAndIncrement)
                   .givenAPublished(new SomeTestSaga.SomeEvent());
        assertThat(startRecordingCount.get(), equalTo(0));

        testSubject.whenTimeElapses(ofSeconds(5));
        assertThat(startRecordingCount.get(), equalTo(1));
    }

    @Test
    void customListenerInvocationErrorHandlerIsUsedInWhenPhase() {
        SomeTestSaga.SomeEvent testEvent = new SomeTestSaga.SomeEvent("some-id", true);
        ListenerInvocationErrorHandler testErrorHandler = (exception, event, eventHandler) ->
                assertEquals(testEvent.getException().getMessage(), exception.getMessage());
        this.testSubject.registerListenerInvocationErrorHandler(testErrorHandler);

        this.testSubject.givenNoPriorActivity()
                        .whenPublishingA(testEvent);
    }

    @Test
    void customListenerInvocationErrorHandlerIsUsedInGivenPhase() {
        SomeTestSaga.SomeEvent testEvent = new SomeTestSaga.SomeEvent("some-id", true);
        ListenerInvocationErrorHandler testErrorHandler = (exception, event, eventHandler) ->
                assertEquals(testEvent.getException().getMessage(), exception.getMessage());

        this.testSubject.registerListenerInvocationErrorHandler(testErrorHandler)
                        .suppressExceptionInGivenPhase(true);

        this.testSubject.givenAPublished(testEvent);
    }

    @Disabled("TODO revise after Saga support is enabled")
    @Test
    void exceptionsAreRethrownAsFixtureExecutionExceptionDuringGivenPhaseWithoutInvokedCustomErrorHandler() {
        SomeTestSaga.SomeEvent testEvent = new SomeTestSaga.SomeEvent("some-id", true);
        ListenerInvocationErrorHandler testErrorHandler = spy(new LoggingErrorHandler());

        this.testSubject.registerListenerInvocationErrorHandler(testErrorHandler);

        assertThrows(FixtureExecutionException.class, () -> this.testSubject.givenAPublished(testEvent));
    }

    @Test
    @Disabled("TODO revise after Saga support is enabled")
    void registeredResourceInjectorIsCalledUponFirstEventPublication() {
        AtomicBoolean assertion = new AtomicBoolean(false);
        testSubject.registerResourceInjector(saga -> assertion.set(true))
                   // Publishing a single event should trigger the creation and injection of resources
                   .givenAPublished(new SomeTestSaga.SomeEvent());

        assertTrue(assertion.get());
    }

    public static class SomeTestSaga {

        @SuppressWarnings("unused")
        @StartSaga
        @SagaEventHandler(associationProperty = "id")
        public void handle(SomeEvent event) throws Exception {
            if (event.shouldThrowException()) {
                throw event.getException();
            }
        }

        public static class SomeEvent {

            private final String id;
            private final Boolean shouldThrowException;
            private final Exception exception = new IllegalStateException("I was told to throw an exception");

            public SomeEvent() {
                this("42", false);
            }

            public SomeEvent(String id, Boolean shouldThrowException) {
                this.id = id;
                this.shouldThrowException = shouldThrowException;
            }

            public String getId() {
                return id;
            }

            Boolean shouldThrowException() {
                return shouldThrowException;
            }

            public Exception getException() {
                return exception;
            }
        }
    }
}
