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

package org.axonframework.messaging.core.annotation;

import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.queryhandling.QueryMessage;

import java.util.Map;

/**
 * Container for message handler attributes. Typically used by
 * {@link MessageHandlingMember} implementations. Stores handler attributes in a
 * {@link Map} of {@link String} to {@link Object}. Some default keys used by {@link HandlerAttributes} implementations,
 * like {@link #MESSAGE_TYPE} can be used to {@link #get(String)} entries.
 *
 * @author Steven van Beelen
 * @since 4.5.0
 */
public interface HandlerAttributes {

    /**
     * Attribute key referencing the {@link Message} type being handled by the handler.
     */
    String MESSAGE_TYPE = "MessageHandler.messageType";
    /**
     * Attribute key referencing the payload type contained in the {@link Message}.
     */
    String PAYLOAD_TYPE = "MessageHandler.payloadType";
    /**
     * Attribute key referencing the name of the {@link CommandMessage} the handler
     * can handle.
     */
    String COMMAND_NAME = "CommandHandler.commandName";
    /**
     * Attribute key referencing the routing key used to route a
     * {@link CommandMessage} to the handler.
     */
    String COMMAND_ROUTING_KEY = "CommandHandler.routingKey";
    /**
     * Attribute key referencing the name of the {@link QueryMessage} the handler can
     * handle.
     */
    String QUERY_NAME = "QueryHandler.queryName";
    /**
     * Attribute key referencing the name of the {@link EventMessage} the handler can
     * handle.
     */
    String EVENT_NAME = "EventHandler.eventName";
    /**
     * Attribute key referencing the result type the handler can handle.
     */
    String RESULT_TYPE = "ResultHandler.resultType";
    /**
     * Attribute key referencing the exception result type the handler can handle.
     */
    String EXCEPTION_RESULT_TYPE = "ExceptionHandler.resultType";
    /**
     * Attribute key reference the command name pattern for a command message interceptor.
     */
    String COMMAND_NAME_PATTERN = "CommandHandlerInterceptor.commandNamePattern";

    /**
     * Attribute key referencing the message type handled by a
     * {@link org.axonframework.messaging.core.interception.annotation.MessageHandlerInterceptor}.
     */
    String INTERCEPTOR_MESSAGE_TYPE = "MessageHandlerInterceptor.messageType";

    /**
     * Attribute key referencing whether the handler forces the creation of a new saga instance.
     */
    String FORCE_NEW_SAGA = "StartSaga.forceNew";
    /**
     * Attribute key referencing the property in the handled {@link EventMessage} to
     * associate a saga instance with.
     */
    String SAGA_ASSOCIATION_PROPERTY = "SagaEventHandler.associationProperty";
    /**
     * Attribute key referencing the saga event handler's association property key name used.
     */
    String SAGA_ASSOCIATION_PROPERTY_KEY_NAME = "SagaEventHandler.keyName";
    /**
     * Attribute key referencing the type of association resolver used by a saga event handler.
     */
    String SAGA_ASSOCIATION_RESOLVER = "SagaEventHandler.associationResolver";

    /**
     * Attribute key referencing an aggregate creation policy to be used when handling a command.
     */
    String AGGREGATE_CREATION_POLICY = "CreationPolicy.creationPolicy";

    /**
     * Attribute key referencing whether the handler is allowed to be invoked on replays.
     */
    String ALLOW_REPLAY = "AllowReplay.allowReplay";

    /**
     * Attribute key referencing the sequencing policy type class for the handler.
     */
    String SEQUENCING_POLICY_TYPE = "SequencingPolicy.type";

    /**
     * Attribute key referencing the sequencing policy parameters for the handler.
     */
    String SEQUENCING_POLICY_PARAMETERS = "SequencingPolicy.parameters";

    /**
     * Attribute key referencing the name of the {@code org.axonframework.deadline.DeadlineMessage} the handler can
     * handle.
     */
    String DEADLINE_NAME = "DeadlineHandler.deadlineName";

    /**
     * Retrieve the attribute for the given {@code attributeKey}. Might be {@code null} if there is no attribute present
     * for the given key.
     *
     * @param attributeKey the attribute key to retrieve an attribute for
     * @param <R>          the type of attribute to retrieve
     * @return the attribute for the given {@code attributeKey}
     */
    <R> R get(String attributeKey);

    /**
     * Retrieve all attributes stored in this {@link HandlerAttributes} object.
     *
     * @return all attributes stored in this {@link HandlerAttributes} object
     */
    Map<String, Object> getAll();

    /**
     * Validates whether the given {@code attributeKey} is present in this object.
     *
     * @param attributeKey the attribute key to validate if it is present in this object
     * @return {@code true} if there is an attribute for the given {@code attributeKey} present, {@code false} otherwise
     */
    boolean contains(String attributeKey);

    /**
     * Validate whether zero attributes are present in this object.
     *
     * @return {@code true} if there are no attributes present, {@code false} otherwise
     */
    boolean isEmpty();

    /**
     * Returns a {@code HandlerAttributes}, merging the attributes in {@code this} instance with the given
     * {@code attributes}. If {@code this} and {@code other} have identical entries, the values from {@code other} will
     * take precedence.
     *
     * @param other The {@code HandlerAttributes} to group with {@code this} instance's attributes.
     * @return A {@code HandlerAttributes} combining {@code this} instance's attributes and the given {@code other}
     * attributes.
     */
    HandlerAttributes mergedWith(HandlerAttributes other);
}
