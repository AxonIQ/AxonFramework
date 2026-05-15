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

package org.axonframework.messaging.eventhandling.processing;

import org.axonframework.common.FutureUtils;
import org.axonframework.common.annotation.Internal;
import org.axonframework.common.infra.ComponentDescriptor;
import org.axonframework.common.infra.DescribableComponent;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.EventHandlingComponent;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.processing.streaming.segmenting.SequencingEventHandlingComponent;
import org.axonframework.messaging.eventhandling.processing.streaming.token.ReplayToken;
import org.axonframework.messaging.eventhandling.processing.streaming.token.TrackingToken;
import org.axonframework.messaging.eventhandling.replay.GenericReplayStatusChanged;
import org.axonframework.messaging.eventhandling.replay.ReplayStatus;
import org.axonframework.messaging.eventhandling.replay.ReplayStatusChanged;
import org.axonframework.messaging.eventhandling.replay.ResetContext;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Internal class for managing multiple {@link EventHandlingComponent} instances and processing event messages through
 * them. Each event handling component is wrapped in a {@link SequencingEventHandlingComponent} to ensure proper
 * sequencing where required.
 * <p>
 * Key responsibilities include:
 * <ul>
 * <li>Distributing event messages to the associated {@link EventHandlingComponent} instances. </li>
 * <li>Ensuring event handling sequencing policies are respected when applicable.</li>
 * <li>Determining support for specific event types across the managed components.</li>
 * </ul>
 *
 * @author Mateusz Nowak
 * @since 5.0.0
 */
@Internal
public class ProcessorEventHandlingComponents implements DescribableComponent {

    private final List<? extends EventHandlingComponent> components;

    /**
     * Constructs a {@code ProcessorEventHandlingComponents} instance by wrapping the provided list of
     * {@link EventHandlingComponent}s in SequencingEventHandlingComponent instances for sequential event handling where
     * needed.
     *
     * @param components The list of {@link EventHandlingComponent}s to be used for event processing. Must not be null
     *                   and is transformed into a list of {@link SequencingEventHandlingComponent}s if necessary.
     */
    public ProcessorEventHandlingComponents(List<EventHandlingComponent> components) {
        Objects.requireNonNull(components, "Components may not be null");
        this.components = components.stream()
                                    .map(c -> c instanceof SequencingEventHandlingComponent
                                            ? c
                                            : new SequencingEventHandlingComponent(c)
                                    ).toList();
    }


    /**
     * Processes a batch of events in the given processing context.
     * <p>
     * Each entry carries its own {@link TrackingToken} in its {@link org.axonframework.messaging.core.Context} under
     * {@link TrackingToken#RESOURCE_KEY}. Before invoking the event handling components for a given entry, that
     * per-event token is written into the shared {@code context} under the same key, overriding the batch-end token
     * that the caller placed there. The {@code context} itself — including its transaction lifecycle and all other
     * resources — is the same object for every event in the batch; only {@link TrackingToken#RESOURCE_KEY} is
     * temporarily overridden for the duration of each event's handling. The batch-end token remains accessible under
     * {@link TrackingToken#BATCH_END_RESOURCE_KEY} throughout.
     * <p>
     * This ensures that per-event replay detection (
     * {@link org.axonframework.messaging.eventhandling.processing.streaming.token.ReplayToken#isReplay(TrackingToken)} and
     * {@link org.axonframework.messaging.eventhandling.processing.streaming.token.ReplayToken#concludesReplay(TrackingToken)}) observes
     * the correct position for each individual event rather than the shared batch-end token. When an entry carries no
     * per-event token (e.g. from a
     * {@link org.axonframework.messaging.eventhandling.processing.subscribing.SubscribingEventProcessor} which has no
     * stream position), the context is used as-is.
     * <p>
     * The result of handling is an {@link MessageStream.Empty empty stream}. It's guaranteed that events with the same
     * {@link #sequenceIdentifiersFor(EventMessage, ProcessingContext)} value are processed by a single component in the
     * order they are received, but sequencing is not preserved across different event handling components — this is
     * intentional, as they may have different sequencing policies.
     *
     * @param entries the batch of message stream entries to be processed
     * @param context the processing context in which the event messages are processed
     * @return a stream of messages resulting from the processing of the event messages
     */
    public MessageStream.Empty<Message> handle(
            List<MessageStream.Entry<? extends EventMessage>> entries,
            ProcessingContext context
    ) {
        MessageStream<Message> batchResult = MessageStream.empty().cast();
        for (var entry : entries) {
            ProcessingContext perEventContext = TrackingToken.fromContext(entry)
                    .map(token -> context.withResource(TrackingToken.RESOURCE_KEY, token))
                    .orElse(context);
            var eventResult = handle(entry.message(), perEventContext);
            batchResult = batchResult.concatWith(eventResult.cast());
        }
        return batchResult.ignoreEntries()
                          .cast();
    }

    private MessageStream.Empty<Message> handle(
            EventMessage event,
            ProcessingContext context
    ) {
        Optional<TrackingToken> token = TrackingToken.fromContext(context);
        boolean isReplaying = token.isPresent() && ReplayToken.isReplay(token.get());

        MessageStream<Message> result = MessageStream.empty();
        for (var component : components) {
            // During replay, skip components that don't support reset
            if (isReplaying && !component.supportsReset()) {
                continue;
            }
            if (component.supports(event.type().qualifiedName())) {
                var componentResult = component.handle(event, context);
                result = result.concatWith(componentResult);
            }
            // Validated deliberately AFTER handling the event; that's the point handling switches from REPLAY to REGULAR
            if (token.isPresent() && ReplayToken.concludesReplay(token.get())) {
                ReplayStatusChanged replayStatusChanged =
                        new GenericReplayStatusChanged(ReplayStatus.REGULAR, (Object) null);
                result = result.concatWith(component.handle(replayStatusChanged, context));
            }
        }
        return result.ignoreEntries().cast();
    }

    /**
     * Retrieves a set of all event names supported by the components comprising this ProcessorEventHandlingComponents
     * instance. Each event is referenced through a QualifiedName.
     *
     * @return A set of QualifiedName objects representing the supported events.
     */
    public Set<QualifiedName> supportedEvents() {
        return components.stream()
                         .flatMap(c -> c.supportedEvents().stream())
                         .collect(Collectors.toSet());
    }

    /**
     * Checks if the specified event name is supported by any of the components.
     *
     * @param eventName The qualified name of the event to be checked. Must not be null.
     * @return true if the event name is supported, false otherwise.
     */
    public boolean supports(QualifiedName eventName) {
        return components.stream().anyMatch(c -> c.supports(eventName));
    }

    /**
     * Retrieves a set of sequence identifiers for the given event message and processing context. Each identifier
     * represents a sequence property determined by the components within this instance.
     *
     * @param event   The event message for which the sequence identifiers are to be determined. Must not be null.
     * @param context The processing context in which the sequence identifiers are evaluated. Must not be null.
     * @return A set of sequence identifiers associated with the given event and context.
     */
    public Set<Object> sequenceIdentifiersFor(EventMessage event, ProcessingContext context) {
        return components.stream()
                         .map(c -> c.sequenceIdentifierFor(event, context))
                         .collect(Collectors.toSet());
    }

    /**
     * Handles reset for all components that support it.
     * <p>
     * This method invokes the reset handler on each component that supports reset operations. All handlers must
     * complete successfully for the operation to succeed.
     *
     * @param resetContext The reset context message.
     * @param context      The processing context.
     * @return A future that completes when all reset handlers have completed.
     */
    public CompletableFuture<Void> handleReset(ResetContext resetContext,
                                               ProcessingContext context) {
        MessageStream<Message> result = MessageStream.empty();

        for (var component : components) {
            if (component.supportsReset()) {
                result = result.concatWith(component.handle(resetContext, context).cast());
            }
        }

        return result.ignoreEntries()
                     .asCompletableFuture()
                     .thenApply(FutureUtils::ignoreResult);
    }

    /**
     * Checks if any component supports reset operations.
     *
     * @return {@code true} if at least one component supports reset, {@code false} otherwise.
     */
    public boolean supportsReset() {
        return components.stream()
                         .anyMatch(EventHandlingComponent::supportsReset);
    }

    /**
     * Handles the start of a replay for all components.
     * <p>
     * This method invokes the
     * {@link org.axonframework.messaging.eventhandling.replay.ReplayStatusChangedHandler ReplayStatusChangedHandlers}
     * on each component. All handlers must complete successfully for the operation to succeed.
     *
     * @param context the processing context
     * @return a future that completes when all
     * {@link org.axonframework.messaging.eventhandling.replay.ReplayStatusChangedHandler ReplayStatusChangedHandlers}
     * have completed
     */
    public CompletableFuture<Void> replayStarted(ProcessingContext context) {
        MessageStream<Message> result = MessageStream.empty();
        ReplayStatusChanged replayStarted =
                new GenericReplayStatusChanged(ReplayStatus.REPLAY, (Object) null);

        for (var component : components) {
            if (component.supportsReset()) {
                result = result.concatWith(component.handle(replayStarted, context).cast());
            }
        }

        return result.ignoreEntries()
                     .asCompletableFuture()
                     .thenApply(FutureUtils::ignoreResult);
    }

    @Override
    public void describeTo(ComponentDescriptor descriptor) {
        descriptor.describeProperty("components", components);
    }
}
