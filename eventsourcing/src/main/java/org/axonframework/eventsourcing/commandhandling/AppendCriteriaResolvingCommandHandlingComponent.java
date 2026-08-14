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

import org.axonframework.common.configuration.ComponentBuilder;
import org.axonframework.common.infra.ComponentDescriptor;
import org.axonframework.eventsourcing.CommandAppendCriteriaResolver;
import org.axonframework.eventsourcing.eventstore.EventStore;
import org.axonframework.messaging.commandhandling.CommandHandlingComponent;
import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.commandhandling.CommandResultMessage;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;

import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * A {@link CommandHandlingComponent} decorator that resolves command append criteria for every command supported by
 * its delegate.
 * <p>
 * The configured {@link CommandAppendCriteriaResolver} is shared by the component, but invoked separately for every
 * command. Resolution is deferred until transaction finalization so the resolver receives all criteria accumulated
 * through sourcing during command handling.
 *
 * @author Mateusz Nowak
 * @since 5.4.0
 */
public final class AppendCriteriaResolvingCommandHandlingComponent implements CommandHandlingComponent {

    private final CommandHandlingComponent delegate;
    private final EventStore eventStore;
    private final CommandAppendCriteriaResolver resolver;

    /**
     * Creates a component decorator that applies the given append-criteria {@code resolver} to every command handled
     * by the {@code delegate}.
     *
     * @param delegate the command-handling component to decorate
     * @param eventStore the event store whose transaction receives the resolved criteria
     * @param resolver the component-level command append-criteria resolver
     */
    public AppendCriteriaResolvingCommandHandlingComponent(CommandHandlingComponent delegate,
                                                           EventStore eventStore,
                                                           CommandAppendCriteriaResolver resolver) {
        this.delegate = requireNonNull(delegate, "The command-handling component cannot be null.");
        this.eventStore = requireNonNull(eventStore, "The EventStore cannot be null.");
        this.resolver = requireNonNull(resolver, "The command append criteria resolver cannot be null.");
    }

    /**
     * Builds a decorated command-handling component from configuration-aware component and resolver builders.
     *
     * @param componentBuilder the builder of the component handling the commands
     * @param resolverBuilder the builder of the resolver applied to every command on the component
     * @return a builder of the decorated command-handling component
     */
    public static ComponentBuilder<CommandHandlingComponent> withAppendCriteria(
            ComponentBuilder<? extends CommandHandlingComponent> componentBuilder,
            ComponentBuilder<? extends CommandAppendCriteriaResolver> resolverBuilder
    ) {
        requireNonNull(componentBuilder, "The command-handling component builder cannot be null.");
        requireNonNull(resolverBuilder, "The command append criteria resolver builder cannot be null.");
        return configuration -> new AppendCriteriaResolvingCommandHandlingComponent(
                componentBuilder.build(configuration),
                configuration.getComponent(EventStore.class),
                resolverBuilder.build(configuration)
        );
    }

    @Override
    public MessageStream.Single<CommandResultMessage> handle(CommandMessage command, ProcessingContext context) {
        try {
            CommandAppendCriteriaOverride.apply(command, context, eventStore, resolver);
            return delegate.handle(command, context);
        } catch (Throwable throwable) {
            return MessageStream.failed(throwable);
        }
    }

    @Override
    public Set<QualifiedName> supportedCommands() {
        return delegate.supportedCommands();
    }

    @Override
    public void describeTo(ComponentDescriptor descriptor) {
        descriptor.describeWrapperOf(delegate);
        descriptor.describeProperty("appendCriteriaResolver", resolver);
    }
}
