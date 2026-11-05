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

import org.axonframework.eventsourcing.eventstore.AppendCondition;
import org.axonframework.eventsourcing.eventstore.EventStore;
import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.core.Context.ResourceKey;
import org.axonframework.messaging.core.MessageHandlerInterceptor;
import org.axonframework.messaging.core.MessageHandlerInterceptorChain;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventstreaming.EventCriteria;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.function.UnaryOperator;

import static java.util.Objects.requireNonNull;

/**
 * A {@link MessageHandlerInterceptor} that lets an {@link AppendCriteriaResolver} decide the append criteria of the
 * commands it intercepts.
 * <p>
 * Before the command is handled, the interceptor registers the resolver on the command's
 * {@link org.axonframework.eventsourcing.eventstore.EventStoreTransaction}, through
 * {@link org.axonframework.eventsourcing.eventstore.EventStoreTransaction#overrideAppendCondition}. The resolver only
 * runs when that transaction appends, so it receives all criteria accumulated through sourcing while the command was
 * handled, and its result replaces them as the command's consistency boundary. The consistency marker obtained while
 * sourcing is kept.
 * <p>
 * This is the declarative counterpart of
 * {@link org.axonframework.eventsourcing.commandhandling.annotation.AppendCriteriaBuilder}, which is backed by this
 * interceptor. Register it on a {@link org.axonframework.messaging.commandhandling.configuration.CommandHandlingModule}
 * for command handlers outside an entity. Register the criteria per command name, the way a
 * {@link org.axonframework.messaging.commandhandling.SimpleCommandHandlingComponent} subscribes handlers; commands
 * with other names keep their sourcing criteria:
 * <pre>{@code
 * configurer.registerCommandHandlingModule(
 *         CommandHandlingModule.named("credits")
 *                              .commandHandlers()
 *                              .commandHandlingComponent(c -> creditComponent)
 *                              .intercepted(c -> AppendCriteriaInterceptor
 *                                      .create()
 *                                      .forCommand(useCredits, sourcing -> sourcing.withEventTypes(creditsUsed))
 *                                      .forCommand(registerUsername, (command, sourcing, context) -> uniqueName))
 * );
 * }</pre>
 * To decide for every intercepted command instead, pass a single resolver to the constructor:
 * {@code new AppendCriteriaInterceptor(resolver)}. Criteria registered through {@code forCommand} on such an
 * interceptor take precedence over that resolver for their command.
 * For an entity configured through an {@link org.axonframework.modelling.entity.EntityMetamodelBuilder}, adapt it with
 * {@link org.axonframework.modelling.entity.EntityCommandHandlerInterceptor#of(MessageHandlerInterceptor)}:
 * <pre>{@code
 * builder.instanceCommandHandler(useCredits, useCreditsHandler)
 *        .commandHandlerInterceptor(EntityCommandHandlerInterceptor.of(new AppendCriteriaInterceptor(resolver)));
 * }</pre>
 * <p>
 * Keep the following in mind when registering it:
 * <ul>
 *     <li>Commands are matched by the {@link QualifiedName} of their type, so the payload is not converted. A resolver
 *     given to the constructor applies to every command the interceptor intercepts: on a {@code CommandHandlingModule}
 *     that is every command of every component in that module, and on an entity it also covers commands forwarded to
 *     its child entities.</li>
 *     <li>At most one resolver applies per command. A command reaching two of these interceptors that both decide its
 *     criteria, or one of them and an {@code AppendCriteriaBuilder}, fails rather than letting one silently win. A
 *     command this interceptor has no criteria for is left to the others.</li>
 *     <li>A command that sourced nothing has its criteria checked from
 *     {@link org.axonframework.eventsourcing.eventstore.ConsistencyMarker#ORIGIN}, so a resolver can reserve a unique
 *     key without a preliminary read. Returning {@link EventCriteria#havingAnyTag()} for such a command keeps it
 *     unchecked. An entity created without loading is the exception: its resolver receives criteria selecting the
 *     tags of the appended events, which are checked from {@code ORIGIN} as well.</li>
 *     <li>The resolver is registered before the command is handled, so an override the handler registers itself
 *     through {@code overrideAppendCondition} is applied after it and receives the resolved criteria.</li>
 *     <li>An aggregate-based event store detects conflicts only through the sequence of each aggregate it appends
 *     to, so the resolved criteria have no effect there.</li>
 * </ul>
 *
 * @author Mateusz Nowak
 * @since 5.5.0
 */
public final class AppendCriteriaInterceptor implements MessageHandlerInterceptor<CommandMessage> {

    private static final ResourceKey<Boolean> APPLIED_KEY = ResourceKey.withLabel("appendCriteriaApplied");

    private final Map<QualifiedName, AppendCriteriaResolver> resolvers = new HashMap<>();
    @Nullable
    private final AppendCriteriaResolver fallback;

    /**
     * Creates an interceptor letting the given {@code resolver} decide the append criteria of every command it
     * intercepts.
     * <p>
     * Criteria registered for a specific command through {@link #forCommand(QualifiedName, AppendCriteriaResolver)}
     * take precedence over this {@code resolver}.
     *
     * @param resolver the resolver deciding the append criteria of each intercepted command
     */
    public AppendCriteriaInterceptor(AppendCriteriaResolver resolver) {
        this.fallback = requireNonNull(resolver, "The append criteria resolver cannot be null.");
    }

    private AppendCriteriaInterceptor() {
        this.fallback = null;
    }

    /**
     * Creates an interceptor without criteria, to register them per command name through
     * {@link #forCommand(QualifiedName, UnaryOperator)} or {@link #forCommand(QualifiedName, AppendCriteriaResolver)}.
     * Commands without registered criteria keep their sourcing criteria.
     *
     * @return an interceptor deciding the append criteria of no command yet
     */
    public static AppendCriteriaInterceptor create() {
        return new AppendCriteriaInterceptor();
    }

    /**
     * Registers the append criteria of the command with the given {@code name}, derived from its sourcing criteria.
     * <p>
     * Use this for criteria that only depend on what the command sourced, such as narrowing them to the event types
     * that can invalidate its decision: {@code sourcing -> sourcing.withEventTypes(creditsUsed)}.
     *
     * @param name     the name of the command to decide the append criteria for
     * @param criteria the function deriving the append criteria from the sourcing criteria
     * @return this interceptor, for fluent interfacing
     * @throws IllegalArgumentException if criteria are already registered for a command with the given {@code name}
     */
    public AppendCriteriaInterceptor forCommand(QualifiedName name, UnaryOperator<EventCriteria> criteria) {
        requireNonNull(criteria, "The append criteria function cannot be null.");
        return forCommand(name, (command, sourcingCriteria, context) -> criteria.apply(sourcingCriteria));
    }

    /**
     * Registers the given {@code resolver} to decide the append criteria of the command with the given {@code name}.
     * <p>
     * Use this for criteria that depend on the command or the context, such as a unique key taken from the payload.
     *
     * @param name     the name of the command to decide the append criteria for
     * @param resolver the resolver deciding the append criteria of that command
     * @return this interceptor, for fluent interfacing
     * @throws IllegalArgumentException if criteria are already registered for a command with the given {@code name}
     */
    public AppendCriteriaInterceptor forCommand(QualifiedName name, AppendCriteriaResolver resolver) {
        requireNonNull(name, "The command name cannot be null.");
        requireNonNull(resolver, "The append criteria resolver cannot be null.");
        if (resolvers.putIfAbsent(name, resolver) != null) {
            throw new IllegalArgumentException(
                    "Append criteria are already registered for command [%s].".formatted(name)
            );
        }
        return this;
    }

    @Override
    public MessageStream<?> interceptOnHandle(CommandMessage command,
                                              ProcessingContext context,
                                              MessageHandlerInterceptorChain<CommandMessage> interceptorChain) {
        AppendCriteriaResolver resolver = resolvers.getOrDefault(command.type().qualifiedName(), fallback);
        if (resolver == null) {
            return interceptorChain.proceed(command, context);
        }
        if (context.putResourceIfAbsent(APPLIED_KEY, Boolean.TRUE) != null) {
            return MessageStream.failed(new IllegalStateException(
                    ("Cannot apply append criteria for command [%s]. Another @AppendCriteriaBuilder or "
                            + "AppendCriteriaInterceptor already defined them for this command.")
                            .formatted(command.type().qualifiedName())
            ));
        }
        context.component(EventStore.class)
               .transaction(context)
               .overrideAppendCondition(current -> override(resolver, command, context, current));
        return interceptorChain.proceed(command, context);
    }

    private static AppendCondition override(AppendCriteriaResolver resolver,
                                            CommandMessage command,
                                            ProcessingContext context,
                                            AppendCondition current) {
        EventCriteria criteria = requireNonNull(resolver.resolve(command, current.criteria(), context),
                                                "The append criteria resolver returned null.");
        if (AppendCondition.none().equals(current)) {
            // Nothing was sourced, so criteria restricting nothing mean no boundary rather than a check from ORIGIN.
            return criteria.hasCriteria() ? AppendCondition.withCriteria(criteria) : AppendCondition.none();
        }
        return current.replaceCriteria(criteria);
    }
}
