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

package org.axonframework.modelling.command.inspection;

import org.jspecify.annotations.Nullable;
import org.axonframework.common.StringUtils;
import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.commandhandling.annotation.CommandHandlingMember;
import org.axonframework.messaging.core.GenericMessage;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.annotation.ChainedMessageHandlerInterceptorMember;
import org.axonframework.messaging.core.interception.annotation.MessageHandlerInterceptorMemberChain;
import org.axonframework.messaging.core.annotation.MessageHandlingMember;
import org.axonframework.messaging.core.interception.annotation.NoMoreInterceptors;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.modelling.entity.ChildEntityNotFoundException;

import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;

/**
 * Implementation of a {@link CommandHandlingMember} that forwards commands to a child entity.
 *
 * @param <P> The parent entity type.
 * @param <C> The child entity type.
 * @author Allard Buijze
 * @since 3.0.0
 */
public class ChildForwardingCommandHandlingMember<P, C> implements ForwardingCommandHandlingMember<P> {

    private final List<MessageHandlingMember<? super C>> childHandlingInterceptors;
    private final MessageHandlingMember<? super C> childHandler;
    private final BiFunction<CommandMessage, P, C> childEntityResolver;
    private final String commandName;
    private final boolean isFactoryHandler;

    /**
     * Initializes a {@code ChildForwardingCommandHandlingMember} that routes commands to a compatible child entity.
     * <p>
     * Child entities are resolved using the given {@code childEntityResolver}. If an entity is found the command will
     * be handled using the given {@code childHandler}.
     *
     * @param childHandlerInterceptors Interceptors for {@code childHandler}.
     * @param childHandler             Handler of the command once a suitable entity is found.
     * @param childEntityResolver      Resolver of child entities for a given command.
     */
    public ChildForwardingCommandHandlingMember(List<MessageHandlingMember<? super C>> childHandlerInterceptors,
                                                MessageHandlingMember<? super C> childHandler,
                                                BiFunction<CommandMessage, P, C> childEntityResolver) {
        this.childHandlingInterceptors = childHandlerInterceptors;
        this.childHandler = childHandler;
        this.childEntityResolver = childEntityResolver;
        this.commandName =
                childHandler.unwrap(CommandHandlingMember.class)
                            .map(cmdh -> StringUtils.emptyOrNull(cmdh.commandName())
                                    ? cmdh.payloadType().getName() : cmdh.commandName())
                            .orElse(null);
        this.isFactoryHandler = childHandler.unwrap(CommandHandlingMember.class)
                                            .map(CommandHandlingMember::isFactoryHandler).orElse(false);
    }

    @Override
    public String commandName() {
        return commandName;
    }

    @Override
    public String routingKey() {
        return null;
    }

    @Override
    public boolean isFactoryHandler() {
        return isFactoryHandler;
    }

    @Override
    public Class<?> payloadType() {
        return childHandler.payloadType();
    }

    @Override
    public int priority() {
        return Integer.MIN_VALUE;
    }

    @Override
    public boolean canForward(CommandMessage message, P target) {
        return childEntityResolver.apply(message, target) != null;
    }

    @Override
    public boolean canHandle(Message message, ProcessingContext context) {
        return childHandler.canHandle(message, context);
    }

    @Override
    public boolean canHandleMessageType(Class<? extends Message> messageType) {
        return childHandler.canHandleMessageType(messageType);
    }

    @Override
    public Object handleSync(Message message,
                             ProcessingContext context,
                             @Nullable P target) throws Exception {
        C childEntity = childEntityResolver.apply((CommandMessage) message, target);
        if (childEntity == null) {
            throw new ChildEntityNotFoundException(
                    "Aggregate cannot handle command [" + message.type()
                            + "], as there is no entity instance within the aggregate to forward it to."
            );
        }
        return interceptorChain(childEntity.getClass()).handleSync(message, childEntity, childHandler);
    }

    @Override
    public MessageStream<?> handle(Message message, ProcessingContext context, @Nullable P target) {
        try {
            Object result = handleSync(message, context, target);
            return MessageStream.just(new GenericMessage(new MessageType(result.getClass()), result));
        } catch (Exception e) {
            return MessageStream.failed(e);
        }
    }

    private MessageHandlerInterceptorMemberChain<C> interceptorChain(Class<?> childType) {
        return childHandlingInterceptors.isEmpty()
                ? NoMoreInterceptors.instance()
                : new ChainedMessageHandlerInterceptorMember<>(childHandlingInterceptors.iterator());
    }

    @SuppressWarnings("unchecked")
    @Override
    public <HT> Optional<HT> unwrap(Class<HT> handlerType) {
        if (handlerType.isInstance(this)) {
            return (Optional<HT>) Optional.of(this);
        }
        return childHandler.unwrap(handlerType);
    }

    @Override
    public <R> Optional<R> attribute(String attributeKey) {
        return childHandler.attribute(attributeKey);
    }
}
