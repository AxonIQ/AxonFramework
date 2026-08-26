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

import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.tracing.Span;

/**
 * Span factory that creates spans for the {@link AbstractSagaManager}. You can customize the spans of the bus by
 * creating your own implementation.
 *
 * @author Mitchell Herrijgers
 * @since 4.9.0
 */
public interface SagaManagerSpanFactory {

    /**
     * Creates a span that represents the creation of a new saga instance.
     *
     * @param event          The event that triggered the creation of the saga.
     * @param sagaType       The type of the saga.
     * @param sagaIdentifier The identifier of the saga.
     * @return The created span.
     */
    Span createCreateSagaInstanceSpan(EventMessage event, Class<?> sagaType, String sagaIdentifier);

    /**
     * Creates a span that represents the invocation of a saga.
     *
     * @param event    The event that triggered the invocation of the saga.
     * @param sagaType The type of the saga.
     * @param saga     The saga that will be invoked.
     * @return The created span.
     */
    Span createInvokeSagaSpan(EventMessage event, Class<?> sagaType, Saga<?> saga);
}
