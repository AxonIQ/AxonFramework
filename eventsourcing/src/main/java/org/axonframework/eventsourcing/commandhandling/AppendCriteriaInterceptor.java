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
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventstreaming.EventCriteria;

import static java.util.Objects.requireNonNull;

/**
 * A {@link MessageHandlerInterceptor} that lets an {@link AppendCriteriaResolver} decide the append criteria of every
 * command it intercepts.
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
 * for command handlers outside an entity:
 * <pre>{@code
 * configurer.registerCommandHandlingModule(
 *         CommandHandlingModule.named("credits")
 *                              .commandHandlers()
 *                              .commandHandlingComponent(c -> creditComponent)
 *                              .intercepted(c -> new AppendCriteriaInterceptor(resolver))
 * );
 * }</pre>
 * For an entity configured through an {@link org.axonframework.modelling.entity.EntityMetamodelBuilder}, adapt it with
 * {@link org.axonframework.modelling.entity.EntityCommandHandlerInterceptor#of(MessageHandlerInterceptor)}:
 * <pre>{@code
 * builder.instanceCommandHandler(useCredits, useCreditsHandler)
 *        .commandHandlerInterceptor(EntityCommandHandlerInterceptor.of(new AppendCriteriaInterceptor(resolver)));
 * }</pre>
 * <p>
 * Keep the following in mind when registering it:
 * <ul>
 *     <li>It applies to every command it intercepts, so the resolver decides per command which criteria to return.
 *     On a {@code CommandHandlingModule} that is every command of every component in that module. On an entity it also
 *     covers commands forwarded to its child entities.</li>
 *     <li>At most one resolver applies per command. A command reaching two of these interceptors, or one of them and
 *     an {@code AppendCriteriaBuilder}, fails rather than letting one silently win.</li>
 *     <li>A command that sourced nothing has its criteria checked from
 *     {@link org.axonframework.eventsourcing.eventstore.ConsistencyMarker#ORIGIN}, so a resolver can reserve a unique
 *     key without a preliminary read. Returning {@link EventCriteria#havingAnyTag()} for such a command keeps it
 *     unchecked. An entity created without loading is the exception: its resolver receives criteria selecting the
 *     tags of the appended events, which are checked from {@code ORIGIN} as well.</li>
 *     <li>An aggregate-based event store detects conflicts only through the sequence of each aggregate it appends
 *     to, so the resolved criteria have no effect there.</li>
 * </ul>
 *
 * @author Mateusz Nowak
 * @since 5.5.0
 */
public final class AppendCriteriaInterceptor implements MessageHandlerInterceptor<CommandMessage> {

    private static final ResourceKey<Boolean> APPLIED_KEY = ResourceKey.withLabel("appendCriteriaApplied");

    private final AppendCriteriaResolver resolver;

    /**
     * Creates an interceptor letting the given {@code resolver} decide the append criteria of every command it
     * intercepts.
     *
     * @param resolver the resolver deciding the append criteria of each intercepted command
     */
    public AppendCriteriaInterceptor(AppendCriteriaResolver resolver) {
        this.resolver = requireNonNull(resolver, "The append criteria resolver cannot be null.");
    }

    @Override
    public MessageStream<?> interceptOnHandle(CommandMessage command,
                                              ProcessingContext context,
                                              MessageHandlerInterceptorChain<CommandMessage> interceptorChain) {
        if (context.putResourceIfAbsent(APPLIED_KEY, Boolean.TRUE) != null) {
            return MessageStream.failed(new IllegalStateException(
                    ("Cannot apply append criteria for command [%s]. Another @AppendCriteriaBuilder or "
                            + "AppendCriteriaInterceptor already defined them for this command.")
                            .formatted(command.type().qualifiedName())
            ));
        }
        context.component(EventStore.class)
               .transaction(context)
               .overrideAppendCondition(current -> override(command, context, current));
        return interceptorChain.proceed(command, context);
    }

    private AppendCondition override(CommandMessage command, ProcessingContext context, AppendCondition current) {
        EventCriteria criteria = requireNonNull(resolver.resolve(command, context, current.criteria()),
                                                "The append criteria resolver returned null.");
        if (AppendCondition.none().equals(current)) {
            // Nothing was sourced, so criteria restricting nothing mean no boundary rather than a check from ORIGIN.
            return criteria.hasCriteria() ? AppendCondition.withCriteria(criteria) : AppendCondition.none();
        }
        return current.replaceCriteria(criteria);
    }
}
