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

package org.axonframework.test.fixture;

import org.jspecify.annotations.Nullable;
import org.axonframework.common.annotation.Internal;
import org.axonframework.common.infra.ComponentDescriptor;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.EventSink;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;


import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * An {@link EventSink} implementation recording all the events that are
 * {@link #publish(ProcessingContext, List) published}.
 * <p>
 * When events are published within a {@link ProcessingContext}, recording is deferred until the context's
 * {@link ProcessingContext#runOnAfterCommit(java.util.function.Consumer) after-commit phase}. As a result, events
 * appended in a unit of work that is rolled back — for example because the command handler threw an exception — are
 * never recorded, mirroring the fact that they are never persisted or published to event handlers. Events published
 * without a context are recorded immediately.
 * <p>
 * The recorded events can then be used to assert expectations with test cases.
 *
 * @author Mateusz Nowak
 * @since 5.0.0
 */
@Internal
public class RecordingEventSink implements EventSink {

    private final List<EventMessage> recorded = new CopyOnWriteArrayList<>();
    protected final EventSink delegate;

    /**
     * Creates a new {@code RecordingEventSink} that will record all events published to the given {@code delegate}.
     *
     * @param delegate The {@link EventSink} to which events will be published.
     */
    public RecordingEventSink(EventSink delegate) {
        this.delegate = Objects.requireNonNull(delegate, "The delegate EventSink may not be null");
    }

    @Override
    public CompletableFuture<Void> publish(@Nullable ProcessingContext context,
                                           List<? extends EventMessage> events) {
        if (context == null) {
            return delegate.publish(null, events)
                           .thenRun(() -> recorded.addAll(events));
        }
        return delegate.publish(context, events)
                       .thenRun(() -> context.runOnAfterCommit(c -> recorded.addAll(events)));
    }

    /**
     * Returns a copied list of all the {@link EventMessage EventMessages}
     * {@link #publish(ProcessingContext, List) published}.
     *
     * @return A copied list of all the {@link EventMessage EventMessages}
     * {@link #publish(ProcessingContext, List) published}.
     */
    public List<EventMessage> recorded() {
        return List.copyOf(recorded);
    }

    /**
     * Resets this recording {@link EventSink}, by removing all recorded {@link EventMessage EventMessages}.
     *
     * @return This recording {@link EventSink}, for fluent interfacing.
     */
    public RecordingEventSink reset() {
        this.recorded.clear();
        return this;
    }

    @Override
    public void describeTo(ComponentDescriptor descriptor) {
        descriptor.describeWrapperOf(delegate);
        descriptor.describeProperty("recorded", recorded);
    }
}
