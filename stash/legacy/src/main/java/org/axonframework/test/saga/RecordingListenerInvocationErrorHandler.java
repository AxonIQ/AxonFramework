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
import org.axonframework.messaging.eventhandling.processing.errorhandling.ListenerInvocationErrorHandler;

import java.util.Optional;

/**
 * A wrapper around a {@link ListenerInvocationErrorHandler} that in itself also implements
 * {@link ListenerInvocationErrorHandler}. Any Exception encountered will be stored, after which the rest of the error
 * handling will be handed off to the wrapped ListenerInvocationErrorHandler.
 *
 * @author Christian Vermorken
 * @since 4.6.0
 */
public class RecordingListenerInvocationErrorHandler implements ListenerInvocationErrorHandler {

    private ListenerInvocationErrorHandler listenerInvocationErrorHandler;

    private boolean started = false;
    private Exception exception;
    private boolean failOnErrorInPreparation = true;

    /**
     * Create a new instance of this class, wrapping another {@link ListenerInvocationErrorHandler}.
     *
     * @param listenerInvocationErrorHandler The {@link ListenerInvocationErrorHandler} to invoke for the error
     *                                       handling, cannot be null.
     */
    public RecordingListenerInvocationErrorHandler(ListenerInvocationErrorHandler listenerInvocationErrorHandler) {
        if (listenerInvocationErrorHandler == null) {
            throw new IllegalArgumentException("listenerInvocationErrorHandler cannot be null");
        }
        this.listenerInvocationErrorHandler = listenerInvocationErrorHandler;
    }

    @Override
    public void onError(Exception exception, EventMessage event,
                        EventMessageHandler eventHandler) throws Exception {
        if (!started && failOnErrorInPreparation) {
            throw exception;
        }
        this.exception = exception;
        listenerInvocationErrorHandler.onError(exception, event, eventHandler);
    }

    /**
     * Start recording by clearing any current {@link Exception}.
     */
    public void startRecording() {
        started = true;
        exception = null;
    }

    /**
     * Sets a new wrapped {@link ListenerInvocationErrorHandler}.
     *
     * @param listenerInvocationErrorHandler The {@link ListenerInvocationErrorHandler} to invoke for the error
     *                                       handling, cannot be null.
     */
    public void setListenerInvocationErrorHandler(ListenerInvocationErrorHandler listenerInvocationErrorHandler) {
        if (listenerInvocationErrorHandler == null) {
            throw new IllegalArgumentException("listenerInvocationErrorHandler cannot be null");
        }
        this.listenerInvocationErrorHandler = listenerInvocationErrorHandler;
    }

    /**
     * Return the last encountered Exception after the startRecording method has been invoked, or an empty Optional of
     * no Exception occurred.
     *
     * @return an Optional of the last encountered Exception
     */
    public Optional<Exception> getException() {
        return Optional.ofNullable(exception);
    }

    /**
     * Configure whether this error handler should fail on errors in the preparation phase. This means recording has not
     * {@link #startRecording() started} yet.
     * <p>
     * When set to {@code true} will rethrow the exception, regardless of the configured
     * {@link ListenerInvocationErrorHandler}. Defaults to {@code true}.
     *
     * @param failOnErrorInPreparation A {@code boolean} dictating whether to rethrow if this recorder is not started.
     */
    public void failOnErrorInPreparation(boolean failOnErrorInPreparation) {
        this.failOnErrorInPreparation = failOnErrorInPreparation;
    }
}
