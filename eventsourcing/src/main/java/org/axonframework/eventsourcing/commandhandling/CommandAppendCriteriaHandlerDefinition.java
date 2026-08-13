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

package org.axonframework.eventsourcing.commandhandling;

import org.axonframework.common.annotation.Internal;
import org.axonframework.common.configuration.Configuration;
import org.axonframework.eventsourcing.eventstore.EventStore;
import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.commandhandling.annotation.CommandHandlingMember;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.annotation.HandlerDefinition;
import org.axonframework.messaging.core.annotation.MessageHandlingMember;
import org.axonframework.messaging.core.annotation.ParameterResolverFactory;
import org.axonframework.messaging.core.annotation.WrappedMessageHandlingMember;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import static java.util.Objects.requireNonNull;

/**
 * Handler-definition decorator that adapts a class-level
 * {@link org.axonframework.eventsourcing.annotation.AppendCriteriaBuilder} to annotated external command handlers.
 *
 * @author Mateusz Nowak
 * @since 5.4.0
 */
@Internal
public final class CommandAppendCriteriaHandlerDefinition implements HandlerDefinition {

    /**
     * Decoration order for the configured {@link HandlerDefinition}.
     */
    public static final int DECORATION_ORDER = 100;

    private final HandlerDefinition delegate;
    private final Configuration configuration;
    private final Map<Class<?>, Optional<AnnotationCommandAppendCriteriaResolver>> resolvers =
            new ConcurrentHashMap<>();

    /**
     * Creates a handler-definition decorator.
     *
     * @param delegate the definition used to create the original handler
     * @param configuration the configuration used to resolve builder components and the event store
     */
    public CommandAppendCriteriaHandlerDefinition(HandlerDefinition delegate, Configuration configuration) {
        this.delegate = requireNonNull(delegate, "The delegate handler definition cannot be null.");
        this.configuration = requireNonNull(configuration, "The configuration cannot be null.");
    }

    @Override
    public <T> Optional<MessageHandlingMember<T>> createHandler(
            Class<T> declaringType,
            Method method,
            ParameterResolverFactory parameterResolverFactory,
            Function<Object, MessageStream<?>> messageStreamResolver
    ) {
        Optional<MessageHandlingMember<T>> original = delegate.createHandler(
                declaringType, method, parameterResolverFactory, messageStreamResolver
        );
        if (original.isEmpty() || !(original.get() instanceof CommandHandlingMember<T> commandHandler)) {
            return original;
        }
        Optional<AnnotationCommandAppendCriteriaResolver> resolver = resolvers.computeIfAbsent(
                declaringType,
                type -> AnnotationCommandAppendCriteriaResolver.inspect(type, configuration)
        );
        return resolver.map(value -> new AppendCriteriaCommandHandlingMember<>(
                               commandHandler,
                               configuration.getComponent(EventStore.class),
                               value
                       ))
                       .map(member -> (MessageHandlingMember<T>) member)
                       .or(() -> original);
    }

    private static final class AppendCriteriaCommandHandlingMember<T>
            extends WrappedMessageHandlingMember<T>
            implements CommandHandlingMember<T> {

        private final CommandHandlingMember<T> delegate;
        private final EventStore eventStore;
        private final AnnotationCommandAppendCriteriaResolver resolver;

        private AppendCriteriaCommandHandlingMember(CommandHandlingMember<T> delegate,
                                                    EventStore eventStore,
                                                    AnnotationCommandAppendCriteriaResolver resolver) {
            super(delegate);
            this.delegate = delegate;
            this.eventStore = eventStore;
            this.resolver = resolver;
        }

        @Override
        public MessageStream<?> handle(Message message, ProcessingContext context, @Nullable T target) {
            if (!(message instanceof CommandMessage command)) {
                return delegate.handle(message, context, target);
            }
            try {
                CommandAppendCriteriaDefinition.apply(command, context, eventStore, resolver);
                return delegate.handle(message, context, target);
            } catch (Throwable throwable) {
                return MessageStream.failed(throwable);
            }
        }

        @Override
        public String commandName() {
            return delegate.commandName();
        }

        @Override
        public String routingKey() {
            return delegate.routingKey();
        }

        @Override
        public boolean isFactoryHandler() {
            return delegate.isFactoryHandler();
        }
    }
}
