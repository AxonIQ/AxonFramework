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

package org.axonframework.messaging.eventhandling.processing.streaming.segmenting;

import org.axonframework.common.annotation.Internal;
import org.axonframework.messaging.eventhandling.DelegatingEventHandlingComponent;
import org.axonframework.messaging.eventhandling.EventHandlingComponent;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.processing.EventProcessor;
import org.axonframework.messaging.core.Context;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * An {@link EventHandlingComponent} wrapper that ensures events with the same sequence identifier are handled
 * sequentially.
 * <p>
 * This component uses the {@link ProcessingContext} to track the last invocation for each sequence identifier. When a
 * new event arrives, it checks if there's an ongoing process for its sequence identifier. If so, it chains the new
 * event's handling to occur after the previous one completes. If not, it handles the event immediately.
 * <p>
 * This class is marked as {@link Internal}, as users are not expected to interact with it directly. Instead,
 * {@link EventProcessor}s will wrap an {@link EventHandlingComponent} in this component to preserve the ordering.
 *
 * @author Allard Buijze
 * @author Mateusz Nowak
 * @author Steven van Beelen
 * @since 5.0.0
 */
@Internal
public class SequencingEventHandlingComponent extends DelegatingEventHandlingComponent {

    private static final Logger logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    private final Context.ResourceKey<Map<Object, CompletableFuture<?>>> sequencedInvocationsKey =
            Context.ResourceKey.withLabel("sequencedInvocations");

    /**
     * Constructs the component with given {@code delegate} to receive calls.
     *
     * @param delegate         The instance to delegate calls to.
     */
    public SequencingEventHandlingComponent(
            EventHandlingComponent delegate
    ) {
        super(delegate);
    }

    @Override
    public MessageStream.Empty<Message> handle(EventMessage event,
                                               ProcessingContext context) {
        Objects.requireNonNull(event, "Event may not be null");
        Objects.requireNonNull(context, "ProcessingContext may not be null");
        Map<Object, CompletableFuture<?>> invocationsBySequenceIdentifier =
                context.computeResourceIfAbsent(sequencedInvocationsKey, ConcurrentHashMap::new);

        Object sequenceIdentifier = sequenceIdentifierFor(event, context);
        var previous = new CompletableFuture<CompletableFuture<?>>();
        //noinspection unchecked
        var invocation = previous.thenCompose(
                previousInvocation -> (CompletableFuture<Message>) chainedSequenceInvocations(
                        sequenceIdentifier, previousInvocation, event, context
                )
        );
        previous.complete(invocationsBySequenceIdentifier.put(sequenceIdentifier, invocation));
        return MessageStream.fromFuture(invocation).ignoreEntries();
    }

    private CompletableFuture<?> chainedSequenceInvocations(
            Object sequenceIdentifier,
            CompletableFuture<?> previousInvocation,
            EventMessage event,
            ProcessingContext context
    ) {
        if (previousInvocation == null) {
            logger.debug("Event [{}] | No previous invocation for sequence identifier [{}]. Handling immediately.",
                         event, sequenceIdentifier);
            return delegate.handle(event, context).asCompletableFuture();
        } else {
            logger.debug(
                    "Event [{}] | Previous invocation found for sequence identifier [{}]. Chaining the current event handling.",
                    event,
                    sequenceIdentifier);
            return previousInvocation.thenCompose(
                    (r) -> delegate.handle(event, context).asCompletableFuture()
            );
        }
    }
}
