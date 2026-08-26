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

import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.EventMessageHandler;
import org.axonframework.messaging.eventhandling.GenericEventMessage;
import org.axonframework.messaging.eventhandling.processing.errorhandling.ListenerInvocationErrorHandler;
import org.axonframework.messaging.core.MessageType;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.*;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Test class validating the {@link RecordingListenerInvocationErrorHandler}.
 *
 * @author Christian Vermorken
 */
class RecordingListenerInvocationErrorHandlerTest {

    private static final EventMessage TEST_EVENT =
            new GenericEventMessage(new MessageType("event"), "test");
    private static final Exception TEST_EXCEPTION = new IllegalArgumentException("This argument is illegal");

    private ListenerInvocationErrorHandler wrappedErrorHandler;
    private EventMessageHandler eventHandler;

    private RecordingListenerInvocationErrorHandler testSubject;

    @BeforeEach
    void setUp() {
        eventHandler = mock(EventMessageHandler.class);
        doReturn(StubSaga.class).when(eventHandler)
                                .getTargetType();

        wrappedErrorHandler = spy(new NoOpListenerInvocationErrorHandler());
        testSubject = new RecordingListenerInvocationErrorHandler(wrappedErrorHandler);
    }

    @Test
    void emptyOnCreation() {
        assertFalse(testSubject.getException().isPresent());
    }

    @Test
    void wrappedHandlerCannotBeNull() {
        assertThrows(IllegalArgumentException.class, () -> new RecordingListenerInvocationErrorHandler(null));
    }

    @Test
    void cannotSetWrappedHandlerToNull() {
        assertThrows(IllegalArgumentException.class, () -> testSubject.setListenerInvocationErrorHandler(null));
    }

    @Test
    void delegatesExceptionToWrappedErrorHandler() throws Exception {
        testSubject.startRecording();

        testSubject.onError(TEST_EXCEPTION, TEST_EVENT, eventHandler);

        Optional<Exception> result = testSubject.getException();
        assertTrue(result.isPresent());
        assertEquals(TEST_EXCEPTION, result.get());
        verify(wrappedErrorHandler).onError(TEST_EXCEPTION, TEST_EVENT, eventHandler);
    }

    @Test
    void clearExceptionOnStartRecording() throws Exception {
        testSubject.startRecording();
        testSubject.onError(TEST_EXCEPTION, TEST_EVENT, eventHandler);
        testSubject.startRecording();

        assertFalse(testSubject.getException().isPresent());
        verify(wrappedErrorHandler).onError(TEST_EXCEPTION, TEST_EVENT, eventHandler);
    }

    @Test
    void byDefaultRethrowsExceptionsIfRecordingHasNotStartedYet() {
        assertThrows(IllegalArgumentException.class,
                     () -> testSubject.onError(TEST_EXCEPTION, TEST_EVENT, eventHandler));
        verifyNoInteractions(wrappedErrorHandler);
    }

    @Test
    void doesNotRethrowExceptionIfRethrowErrorsWhenNotStartedIsDisabled() throws Exception {
        testSubject.failOnErrorInPreparation(false);

        testSubject.onError(TEST_EXCEPTION, TEST_EVENT, eventHandler);

        Optional<Exception> result = testSubject.getException();
        assertTrue(result.isPresent());
        assertEquals(TEST_EXCEPTION, result.get());
        verify(wrappedErrorHandler).onError(TEST_EXCEPTION, TEST_EVENT, eventHandler);
    }

    private static class NoOpListenerInvocationErrorHandler implements ListenerInvocationErrorHandler {

        @Override
        public void onError(@NonNull Exception exception, @NonNull EventMessage event,
                            @NonNull EventMessageHandler eventHandler) throws Exception {
            // No-op
        }
    }
}