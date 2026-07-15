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

package org.axonframework.messaging.eventhandling.gateway;

import org.axonframework.common.infra.DescribableComponent;
import org.axonframework.common.configuration.Configuration;
import org.axonframework.messaging.eventhandling.EventSink;
import org.axonframework.messaging.eventhandling.annotation.EventAppenderParameterResolverFactory;
import org.axonframework.messaging.core.MessageTypeResolver;
import org.axonframework.messaging.core.Metadata;
import org.axonframework.messaging.core.annotation.MessageHandler;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;


import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Component that appends events to an {@link EventSink} in the context of a {@link ProcessingContext}. This makes the
 * {@code EventAppender} the <b>preferred</b> way to append events from within another message handling method.
 * <p>
 * The events will be appended in the context this appender was created for. You can construct one through the
 * {@link #forContext(ProcessingContext)}.
 * <p>
 * When using annotation-based {@link MessageHandler @MessageHandler-methods} and you have declared an argument of type
 * {@link EventAppender}, the appender will automatically be injected by the
 * {@link EventAppenderParameterResolverFactory}.
 * <p>
 * As this component is {@link ProcessingContext}-scoped, it is not retrievable from the {@link Configuration}.
 *
 * @author Mitchell Herrijgers
 * @since 5.0.0
 */
public interface EventAppender extends DescribableComponent {

    /**
     * Creates an appender for the given {@link ProcessingContext}.
     * <p>
     * Use this from within a message handler, or any other method that receives a {@link ProcessingContext}, instead
     * of appending directly through an {@link EventSink}: the appender returned here is bound to that
     * {@code context}, so every event it appends is published with that context's resources correctly applied.
     * <p>
     * Every invocation returns a fresh instance bound to the given {@code context} - it is never cached or shared
     * with another call, even for the same {@code context}. Use the returned appender only for operations belonging
     * to that {@code context}: if {@code context} is a branch created via {@link ProcessingContext#withResource}
     * (e.g. one such branch per event in a streaming processor's batch), each branch must resolve its own appender
     * rather than risk one branch's appender leaking into another's.
     *
     * @param context The {@link ProcessingContext} to create the appender for.
     * @return A fresh appender specific for the given {@code context}.
     */
    static EventAppender forContext(ProcessingContext context) {
        return forContext(context, context.component(EventSink.class), context.component(MessageTypeResolver.class));
    }

    /**
     * Creates an appender for the given {@link ProcessingContext} and {@link EventSink}.
     * <p>
     * Use this from within a message handler, or any other method that receives a {@link ProcessingContext}, instead
     * of appending directly through an {@link EventSink}: the appender returned here is bound to that
     * {@code context}, so every event it appends is published with that context's resources correctly applied.
     * <p>
     * Every invocation returns a fresh instance bound to the given {@code context} - it is never cached or shared
     * with another call, even for the same {@code context}. Use the returned appender only for operations belonging
     * to that {@code context}: if {@code context} is a branch created via {@link ProcessingContext#withResource}
     * (e.g. one such branch per event in a streaming processor's batch), each branch must resolve its own appender
     * rather than risk one branch's appender leaking into another's.
     *
     * @param context             The {@link ProcessingContext} to create the appender for.
     * @param eventSink           The {@link EventSink} to use for the appender.
     * @param messageTypeResolver The {@link MessageTypeResolver} to use for the appender.
     * @return A fresh appender specific for the given {@code context}.
     */
    static EventAppender forContext(
            ProcessingContext context,
            EventSink eventSink,
            MessageTypeResolver messageTypeResolver
    ) {
        Objects.requireNonNull(context, "ProcessingContext may not be null");
        return new ProcessingContextEventAppender(context, eventSink, messageTypeResolver);
    }

    /**
     * Append a collection of events to the event store in the current {@link ProcessingContext}. The events will be
     * published when the context commits.
     *
     * @param events The collection of events to publish.
     */
    default void append(Object... events) {
        append(Arrays.asList(events));
    }

    /**
     * Append a collection of events to the event store in the current {@link ProcessingContext}. The events will be
     * published when the context commits.
     *
     * @param events The collection of events to publish.
     */
    void append(List<?> events);

    /**
     * Append a collection of events to the event store in the current {@link ProcessingContext}, all with the given
     * {@link Metadata}. If an event is already a {@link org.axonframework.messaging.core.Message}, the provided
     * metadata is merged with its existing metadata (provided values take precedence on conflict).
     *
     * @param events   The collection of events to publish.
     * @param metadata The metadata to attach to every event.
     */
    void append(List<?> events, Metadata metadata);

    /**
     * Append a single event to the event store in the current {@link ProcessingContext} with the given
     * {@link Metadata}. If the event is already a {@link org.axonframework.messaging.core.Message}, the provided
     * metadata is merged with its existing metadata (provided values take precedence on conflict).
     *
     * @param event    The event to publish.
     * @param metadata The metadata to attach to the event.
     */
    default void append(Object event, Metadata metadata) {
        append(List.of(event), metadata);
    }
}
