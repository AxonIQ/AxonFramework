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

package org.axonframework.messaging.tracing;

import org.axonframework.messaging.core.Message;
import org.jspecify.annotations.Nullable;

import java.util.function.Supplier;

/**
 * The {@link SpanFactory} is responsible for making {@link Span spans} in a way the chosen tracing provider is
 * compatible with.
 * <p>
 * Each span has an operation name and span kind. From the operation name it should be clear what is happening in the
 * application. For example, use {@code "ClassName.method MessageName"} to indicate a message payload being handled.
 *
 * <p>
 * Spans can have tags, which are provided by {@link SpanAttributesProvider SpanAttributesProviders}. By default, any
 * time a message is used while creating a span should invoke all configured
 * {@link SpanAttributesProvider SpanAttributesProviders} and set those tags on the created span.
 *
 * <p>
 * The factory should support these types of spans:
 * <ul>
 *     <li>New root trace spans: These create an entirely new trace without parent. Use this for asynchronous calls that we want to measure the performance individually of. For example, snapshotting (which has no influence on a business process).</li>
 *     <li>New handler spans: This creates a new span in an already existing trace. The span that was active when the message was dispatched will be linked to it. It will be of the handling type</li>
 *     <li>New dispatch spans: This creates a new span in an already existing trace. It will be of a dispatching type. </li>
 *     <li>New internal span: This creates a new sub-span in an already active span. Use this for measuring individual parts of an already existing span. For example, measuring how long it takes to load the aggregate when handling an event.</li>
 * </ul>
 * <p>
 * Check the individual method's javadoc more information on each type.
 *
 * @author Mitchell Herrijgers
 * @since 4.6.0
 */
public interface SpanFactory {

    /**
     * Creates a new {@link Span} without any parent trace. This should be used for logical start point of asynchronous
     * calls that are not related to a message. For example snapshotting an aggregate.
     * <p>
     * In monitoring systems, this Span will be the root of the trace.
     *
     * @param operationNameSupplier Supplier of the operation's name.
     * @return The created {@link Span}.
     */
    Span createRootTrace(Supplier<String> operationNameSupplier);

    /**
     * Creates a new {@link Span} which becomes its own separate trace, linked to the previous span. Useful for
     * asynchronous invocations, such as handling an event in a StreamingEventProcessor.
     * <p>
     * In monitoring systems, this Span will start a separate trace linked to the previous one.
     * <p>
     * The message's name will be concatenated with the {@code operationName}, see
     * {@link SpanUtils#determineMessageName(Message)}.
     *
     * @param operationNameSupplier Supplier of the operation's name.
     * @param parentMessage The message that is being handled.
     * @param linkedParents Optional parameter, providing this will link the provided message(s) to the current, in
     *                      addition to the original.
     * @return The created {@link Span}.
     */
    default Span createLinkedHandlerSpan(Supplier<String> operationNameSupplier, Message parentMessage, Message... linkedParents) {
        return createHandlerSpan(operationNameSupplier, parentMessage, false, linkedParents);
    }

    /**
     * Creates a new {@link Span} which is part of the current trace. The message attributes will be added to the span,
     * based on the provided {@link SpanAttributesProvider SpanAttributesProviders} for additional debug information.
     * <p>
     * In monitoring systems, this Span will be part of another trace.
     * <p>
     * The message's name will be concatenated with the {@code operationName}, see
     * {@link SpanUtils#determineMessageName(Message)}.
     *
     * @param operationNameSupplier Supplier of the operation's name.
     * @param parentMessage The message that is being handled.
     * @param linkedParents Optional parameter, providing this will link the provided message(s) to the current, in
     *                      addition to the original.
     * @return The created {@link Span}.
     */
    default Span createChildHandlerSpan(Supplier<String> operationNameSupplier, Message parentMessage, Message... linkedParents) {
        return createHandlerSpan(operationNameSupplier, parentMessage, true, linkedParents);
    }

    /**
     * Creates a new {@link Span} linked to asynchronously handling a {@link Message}, for example when handling a
     * command from Axon Server. The message attributes will be added to the span, based on the provided
     * {@link SpanAttributesProvider SpanAttributesProviders} for additional debug information.
     * <p>
     * In monitoring systems, this Span will be the root of the trace.
     * <p>
     * The message's name will be concatenated with the {@code operationName}, see
     * {@link SpanUtils#determineMessageName(Message)}.
     *
     * @param operationNameSupplier Supplier of the operation's name.
     * @param parentMessage The message that is being handled.
     * @param isChildTrace  Whether to force the span to be a part of the current trace. This means not linking, but
     *                      setting a parent.
     * @param linkedParents Optional parameter, providing this will link the provided message(s) to the current, in
     *                      addition to the original.
     * @return The created {@link Span}.
     */
    Span createHandlerSpan(Supplier<String> operationNameSupplier, Message parentMessage, boolean isChildTrace,
                           Message @Nullable... linkedParents);

    /**
     * Creates a new {@link Span} linked to dispatching a {@link Message}, for example when sending a command to Axon
     * Server. The message attributes will be added to the span, based on the provided
     * {@link SpanAttributesProvider SpanAttributesProviders} for additional debug information.
     * <p>
     * In monitoring systems, this Span will be part of another trace.
     * <p>
     * Before asynchronously dispatching a message, add the tracing context to the message, using
     * {@link #propagateContext(Message)} to the message's metadata.
     * <p>
     * The message's name will be concatenated with the {@code operationName}, see
     * {@link SpanUtils#determineMessageName(Message)}.
     *
     * @param operationNameSupplier Supplier of the operation's name.
     * @param parentMessage  The message that is being handled.
     * @param linkedSiblings Optional parameter, providing this will link the provided messages to the current.
     * @return The created {@link Span}.
     */
    Span createDispatchSpan(Supplier<String> operationNameSupplier, Message parentMessage, Message @Nullable ... linkedSiblings);

    /**
     * Creates a new {@link Span} linked to the currently active span. This is useful for tracing different parts of
     * framework logic, so we can time what has the most impact.
     * <p>
     * In monitoring systems, this Span will be part of another trace.
     *
     * @param operationNameSupplier Supplier of the operation's name.
     * @return The created {@link Span}.
     */
    Span createInternalSpan(Supplier<String> operationNameSupplier);

    /**
     * Creates a new {@link Span} linked to the currently active span. This is useful for tracing different parts of
     * framework logic, so we can time what has the most impact.
     * <p>
     * The message supplied is used to provide a clearer name, based on {@link SpanUtils#determineMessageName(Message)},
     * and to add the message's attributes to the span.
     * <p>
     * In monitoring systems, this Span will be part of another trace.
     *
     * @param operationNameSupplier supplier of the operation's name
     * @param message               the message to use to create a clearer name
     * @return the created {@link Span}
     */
    Span createInternalSpan(Supplier<String> operationNameSupplier, Message message);

    /**
     * Registers an additional {@link SpanAttributesProvider} to the factory.
     *
     * @param provider The provider to add.
     */
    void registerSpanAttributeProvider(SpanAttributesProvider provider);

    /**
     * Propagates the currently active trace and span to the message. It should do so in a way that the context can be
     * retrieved by the {@link #createLinkedHandlerSpan(Supplier, Message, Message[])} method. The most efficient method
     * currently known is to enhance the message's metadata.
     * <p>
     * Since messages are immutable, the method returns the enhanced message. This enhanced message should be used
     * during dispatch instead of the original message.
     *
     * @param message The message to enhance.
     * @param <M>     The message's type.
     * @return The enhanced message.
     */
    <M extends Message> M propagateContext(M message);
}
