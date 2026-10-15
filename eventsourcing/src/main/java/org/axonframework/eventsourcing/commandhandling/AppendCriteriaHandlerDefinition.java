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
import org.axonframework.eventsourcing.commandhandling.annotation.AppendCriteriaBuilder;
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
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import static java.util.Objects.requireNonNull;

/**
 * Handler-definition decorator that adapts
 * {@link org.axonframework.eventsourcing.commandhandling.annotation.AppendCriteriaBuilder} methods to annotated
 * external and entity-owned command handlers, matching each handler with the builder for its command type.
 * <p>
 * This class is internal because the framework wires it: the event sourcing configuration registers it as a decorator
 * of the configured {@link HandlerDefinition}, so annotated handlers pick up their builders without user setup. Users
 * declare builders with the annotation, or register an {@link AppendCriteriaInterceptor}, rather than creating this
 * definition themselves.
 *
 * @author Mateusz Nowak
 * @since 5.5.0
 */
@Internal
public final class AppendCriteriaHandlerDefinition implements HandlerDefinition {

    /**
     * Decoration order for the configured {@link HandlerDefinition}.
     * <p>
     * A {@code HandlerDefinition} added through
     * {@link org.axonframework.messaging.core.configuration.reflection.HandlerDefinitionUtils} (order {@code 0} by
     * default) decorates the component into a
     * {@link org.axonframework.messaging.core.annotation.MultiHandlerDefinition}. Handlers created by a definition
     * added outside this decorator would skip builder matching without any error, so
     * this decorator is applied as late as possible: right before the
     * {@link org.axonframework.messaging.core.reflection.HierarchicalHandlerDefinitionConfigurationEnhancer} (order
     * {@code Integer.MAX_VALUE >> 1}), which adds the parent configuration's definitions and has to stay outermost.
     */
    public static final int DECORATION_ORDER = (Integer.MAX_VALUE >> 1) - 1;

    private final HandlerDefinition delegate;
    private final Configuration configuration;
    private final Map<Class<?>, Optional<AnnotationBasedAppendCriteriaResolver.VisibleBuilders>> builders =
            new ConcurrentHashMap<>();
    private final Set<Class<?>> checkedForInheritedHandlers = ConcurrentHashMap.newKeySet();

    /**
     * Creates a handler-definition decorator.
     *
     * @param delegate      the definition used to create the original handler
     * @param configuration the configuration used to resolve builder components and the event store
     */
    public AppendCriteriaHandlerDefinition(HandlerDefinition delegate, Configuration configuration) {
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
        checkBuildersAgainstInheritedHandlers(declaringType, parameterResolverFactory, messageStreamResolver);
        Optional<MessageHandlingMember<T>> original = delegate.createHandler(
                declaringType, method, parameterResolverFactory, messageStreamResolver
        );
        // a compiler-generated bridge carries the annotation of the method it bridges, which is matched on its own
        if (original.isEmpty() || method.isBridge()) {
            return original;
        }
        // other enhancers may already have wrapped the command handler, so look for it rather than at the outer member
        Optional<CommandHandlingMember<?>> commandHandler =
                original.get().unwrap(CommandHandlingMember.class).map(member -> member);
        if (commandHandler.isEmpty()) {
            return original;
        }
        Optional<AnnotationBasedAppendCriteriaResolver.VisibleBuilders> visibleBuilders = builders.computeIfAbsent(
                declaringType, type -> AnnotationBasedAppendCriteriaResolver.inspect(type, configuration)
        );
        if (visibleBuilders.isEmpty()) {
            return original;
        }
        // the member's payload type is the effective one, narrowed by both the annotation and the parameters
        AnnotationBasedAppendCriteriaResolver resolver =
                visibleBuilders.get().resolverFor(original.get().payloadType());
        return Optional.of(new AppendCriteriaCommandHandlingMember<>(
                original.get(),
                commandHandler.get(),
                configuration.getComponent(EventStore.class),
                resolver
        ));
    }

    /**
     * Runs once per class, also for a class declaring builders without any handler of its own, as that is exactly the
     * class whose builders an inherited handler never reaches. Marked checked only after passing, so a repeated
     * inspection of an invalid class fails again.
     */
    private void checkBuildersAgainstInheritedHandlers(Class<?> declaringType,
                                                       ParameterResolverFactory parameterResolverFactory,
                                                       Function<Object, MessageStream<?>> messageStreamResolver) {
        if (checkedForInheritedHandlers.contains(declaringType)) {
            return;
        }
        boolean declaresBuilders = Arrays.stream(declaringType.getDeclaredMethods())
                                         .anyMatch(method -> method.isAnnotationPresent(AppendCriteriaBuilder.class));
        if (declaresBuilders) {
            Set<Class<?>> redeclaredPayloads = new HashSet<>(
                    commandHandlers(declaringType, parameterResolverFactory, messageStreamResolver).values()
            );
            Map<Method, Class<?>> inheritedHandlers = new HashMap<>();
            for (Class<?> type = declaringType.getSuperclass();
                 type != null && type != Object.class;
                 type = type.getSuperclass()) {
                commandHandlers(type, parameterResolverFactory, messageStreamResolver).forEach(
                        (handler, payloadType) -> {
                            if (!redeclaredPayloads.contains(payloadType)) {
                                inheritedHandlers.put(handler, payloadType);
                            }
                        }
                );
            }
            AnnotationBasedAppendCriteriaResolver.rejectBuildersInheritedHandlersNeverUse(
                    declaringType, inheritedHandlers, configuration
            );
        }
        checkedForInheritedHandlers.add(declaringType);
    }

    /**
     * Creates the members for the methods {@code type} declares through the delegate, the same way the handlers
     * themselves are created, and returns the command handlers among them with the payload type each one handles.
     */
    private <T> Map<Method, Class<?>> commandHandlers(Class<T> type,
                                                     ParameterResolverFactory parameterResolverFactory,
                                                     Function<Object, MessageStream<?>> messageStreamResolver) {
        Map<Method, Class<?>> handlers = new HashMap<>();
        for (Method method : type.getDeclaredMethods()) {
            if (method.isBridge()) {
                continue;
            }
            delegate.createHandler(type, method, parameterResolverFactory, messageStreamResolver)
                    .filter(member -> member.unwrap(CommandHandlingMember.class).isPresent())
                    .ifPresent(member -> handlers.put(method, member.payloadType()));
        }
        return handlers;
    }

    private static final class AppendCriteriaCommandHandlingMember<T>
            extends WrappedMessageHandlingMember<T>
            implements CommandHandlingMember<T> {

        private final MessageHandlingMember<T> delegate;
        private final CommandHandlingMember<?> commandHandler;
        private final EventStore eventStore;
        private final AnnotationBasedAppendCriteriaResolver resolver;

        private AppendCriteriaCommandHandlingMember(MessageHandlingMember<T> delegate,
                                                    CommandHandlingMember<?> commandHandler,
                                                    EventStore eventStore,
                                                    AnnotationBasedAppendCriteriaResolver resolver) {
            super(delegate);
            this.delegate = delegate;
            this.commandHandler = commandHandler;
            this.eventStore = eventStore;
            this.resolver = resolver;
        }

        @Override
        public MessageStream<?> handle(Message message, ProcessingContext context, @Nullable T target) {
            if (!(message instanceof CommandMessage command)) {
                return delegate.handle(message, context, target);
            }
            try {
                AppendCriteriaOverride.apply(command, context, eventStore, resolver);
                return delegate.handle(message, context, target);
            } catch (Exception e) {
                return MessageStream.failed(e);
            }
        }

        @Override
        public String commandName() {
            return commandHandler.commandName();
        }

        @Override
        public String routingKey() {
            return commandHandler.routingKey();
        }

        @Override
        public boolean isFactoryHandler() {
            return commandHandler.isFactoryHandler();
        }
    }
}
