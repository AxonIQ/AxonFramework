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

import org.axonframework.eventsourcing.eventstore.EventStore;
import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.core.MessageHandlerInterceptor;
import org.axonframework.messaging.core.MessageHandlerInterceptorChain;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;

import static java.util.Objects.requireNonNull;

/**
 * A {@link MessageHandlerInterceptor} that applies an {@link AppendCriteriaResolver} to every command it intercepts.
 * <p>
 * This is the declarative counterpart of
 * {@link org.axonframework.eventsourcing.commandhandling.annotation.AppendCriteriaBuilder}. Before the command is
 * handled, the interceptor registers the resolver on the command's
 * {@link org.axonframework.eventsourcing.eventstore.EventStoreTransaction}. The resolver itself only runs when that
 * transaction commits, so it receives all criteria accumulated through sourcing while the command was handled, and its
 * result replaces them as that command's append criteria.
 * <p>
 * Register it on a {@link org.axonframework.messaging.commandhandling.configuration.CommandHandlingModule} for
 * command handlers outside an entity:
 * <pre>{@code
 * configurer.registerCommandHandlingModule(
 *         CommandHandlingModule.named("credits")
 *                              .commandHandlers()
 *                              .commandHandlingComponent(c -> creditComponent)
 *                              .intercepted(c -> new AppendCriteriaInterceptor(c.getComponent(EventStore.class),
 *                                                                              resolver))
 * );
 * }</pre>
 * For an entity configured through an {@link org.axonframework.modelling.entity.EntityMetamodelBuilder}, adapt it with
 * {@link org.axonframework.modelling.entity.EntityCommandHandlerInterceptor#of(MessageHandlerInterceptor)}:
 * <pre>{@code
 * builder.instanceCommandHandler(useCredits, useCreditsHandler)
 *        .commandHandlerInterceptor(EntityCommandHandlerInterceptor.of(
 *                new AppendCriteriaInterceptor(configuration.getComponent(EventStore.class), resolver)
 *        ));
 * }</pre>
 * <p>
 * Keep the following in mind when registering it:
 * <ul>
 *     <li>It applies to every command it intercepts. On a {@code CommandHandlingModule} that is every command of every
 *     component in that module. On an entity it also covers commands forwarded to its child entities. On a
 *     polymorphic entity, the interceptors of the polymorphic metamodel only cover the commands its shared supertype
 *     handles, so register it on each concrete type's metamodel too.</li>
 *     <li>At most one resolver applies per command. A command reaching both this interceptor and an
 *     {@code AppendCriteriaBuilder}, or two of these interceptors, fails rather than letting one silently win.</li>
 *     <li>The resolver receives the command as dispatched. When it arrives serialized, for example through a
 *     distributed command bus, its payload is not converted yet, see {@link AppendCriteriaResolver}.</li>
 * </ul>
 *
 * @author Mateusz Nowak
 * @since 5.5.0
 */
public final class AppendCriteriaInterceptor implements MessageHandlerInterceptor<CommandMessage> {

    private final EventStore eventStore;
    private final AppendCriteriaResolver resolver;

    /**
     * Creates an interceptor applying the given append-criteria {@code resolver} to every command it intercepts.
     *
     * @param eventStore the event store whose transaction receives the resolved criteria
     * @param resolver   the resolver deciding the append criteria of each intercepted command
     */
    public AppendCriteriaInterceptor(EventStore eventStore, AppendCriteriaResolver resolver) {
        this.eventStore = requireNonNull(eventStore, "The EventStore cannot be null.");
        this.resolver = requireNonNull(resolver, "The append criteria resolver cannot be null.");
    }

    @Override
    public MessageStream<?> interceptOnHandle(CommandMessage command,
                                              ProcessingContext context,
                                              MessageHandlerInterceptorChain<CommandMessage> interceptorChain) {
        try {
            AppendCriteriaOverride.apply(command, context, eventStore, resolver);
            return interceptorChain.proceed(command, context);
        } catch (Exception e) {
            return MessageStream.failed(e);
        }
    }
}
