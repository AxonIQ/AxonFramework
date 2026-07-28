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

package org.axonframework.hunt.workload;

import org.axonframework.eventsourcing.eventstore.EventStore;
import org.axonframework.eventsourcing.eventstore.EventStoreTransaction;
import org.axonframework.eventsourcing.eventstore.TagResolver;
import org.axonframework.hunt.history.HistoryOps;
import org.axonframework.hunt.history.HistoryRecorder;
import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.commandhandling.CommandResultMessage;
import org.axonframework.messaging.commandhandling.GenericCommandMessage;
import org.axonframework.messaging.commandhandling.GenericCommandResultMessage;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.sequencing.SequencingPolicy;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.DelegatingEventHandlingComponent;
import org.axonframework.messaging.eventhandling.EventHandlingComponent;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.GenericEventMessage;
import org.axonframework.messaging.eventhandling.SimpleEventHandlingComponent;
import org.axonframework.messaging.eventstreaming.Tag;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Events written to a handful of independent streams, with the sequencing policy the arm is about.
 * <p>
 * The whole point of the workload is that the events are independent: nothing in the store forces one stream's events
 * to be handled before another's, so whatever ordering the read side exhibits is the sequencing policy's doing and not
 * the workload's. What the arms then differ in is only which policy the handling component was built with.
 * <p>
 * Every delivery carries the sequence identifier the framework resolved for that event, taken from the framework's own
 * call rather than recomputed, so an ordering oracle judges the identifiers the processor actually routed by. When
 * resolving one throws, that is recorded too: a policy the framework cannot resolve an identifier from is a result,
 * not a crash to be swallowed.
 *
 * @author Stefan Dragisic
 * @since 5.3.0
 */
public final class SequencedWorkload implements Workload {

    /**
     * The tag key every event this workload appends carries.
     */
    public static final String STREAM_TAG = "stream";

    /**
     * The value key the number of sequence-identifier resolutions that threw is recorded under.
     */
    public static final String UNRESOLVED_KEYS = "unresolvedSequenceKeys";

    /**
     * The value key the number of distinct sequence identifiers the run resolved is recorded under.
     */
    public static final String DISTINCT_KEYS = "distinctSequenceKeys";

    private static final String WRITER = "writer-0";
    private static final int STREAMS = 6;
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(15);
    private static final int FAILURE_SAMPLE_LIMIT = 3;

    private final String arm;
    private final SequencingPolicy<? super EventMessage> policy;
    private final Map<String, String> resolvedKeys = new ConcurrentHashMap<>();
    private final Set<String> distinctKeys = ConcurrentHashMap.newKeySet();
    private final AtomicLong delivered = new AtomicLong();
    private final AtomicLong unresolved = new AtomicLong();

    private SequencedWorkload(String arm, SequencingPolicy<? super EventMessage> policy) {
        this.arm = Objects.requireNonNull(arm, "The arm cannot be null.");
        this.policy = Objects.requireNonNull(policy, "The policy cannot be null.");
    }

    /**
     * Creates the workload with the given sequencing policy.
     *
     * @param arm    the arm's name, recorded in the history header so a verdict names the policy it judged
     * @param policy the policy the event-handling component is built with
     * @return the workload
     */
    public static SequencedWorkload with(String arm, SequencingPolicy<? super EventMessage> policy) {
        return new SequencedWorkload(arm, policy);
    }

    @Override
    public String id() {
        return "sequenced";
    }

    @Override
    public TagResolver tagResolver() {
        return event -> event.payload() instanceof Ticked ticked
                ? Set.of(new Tag(STREAM_TAG, ticked.stream()))
                : Set.of();
    }

    @Override
    public Map<String, String> describe(long seed, int commands) {
        Map<String, String> described = new LinkedHashMap<>();
        described.put("workload", id());
        described.put("commands", String.valueOf(commands));
        described.put("streams", String.valueOf(STREAMS));
        described.put("sequencingPolicy", policy.getClass().getSimpleName());
        described.put("arm", arm);
        return Map.copyOf(described);
    }

    @Override
    public List<String> participants(long seed, int commands, org.axonframework.hunt.harness.DeterminismMode mode) {
        return List.of(WRITER);
    }

    @Override
    public EventHandlingComponent install(WorkloadContext context) {
        EventStore eventStore = context.eventStore();
        context.world().commandBus()
               .subscribe(new QualifiedName(Tick.class),
                          (command, processingContext) -> handle(eventStore, command, processingContext));

        HistoryRecorder.ProcessRecorder projection = context.recorder().forProcess("projection", null);
        EventHandlingComponent component =
                SimpleEventHandlingComponent.create("sequenced-projection", policy)
                                            .subscribe(new QualifiedName(Ticked.class),
                                                       (event, ctx) -> project(projection, event));
        return new SequenceRecordingComponent(component, projection);
    }

    @Override
    public void run(WorkloadContext context) throws InterruptedException {
        HistoryRecorder.ProcessRecorder recorder = context.recorder().forProcess(WRITER, null);
        for (int index = 0; index < context.commands() && !context.deadline().expired(); index++) {
            issue(context, recorder, index);
        }
    }

    @Override
    public boolean quiesced(WorkloadContext context) {
        return delivered.get() >= context.world().store().storedEvents();
    }

    @Override
    public void recordFinalState(WorkloadContext context) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("deliveredEvents", delivered.get());
        state.put(UNRESOLVED_KEYS, unresolved.get());
        state.put(DISTINCT_KEYS, distinctKeys.size());
        state.put("arm", arm);
        context.recorder().forProcess("projection", null).info(HistoryOps.PROJECTION, null, Map.copyOf(state));
    }

    /**
     * Returns how many of the run's events were delivered to the projection.
     *
     * @return the delivery count
     */
    public long delivered() {
        return delivered.get();
    }

    /**
     * Returns how often resolving a sequence identifier threw.
     *
     * @return the number of failed resolutions
     */
    public long unresolved() {
        return unresolved.get();
    }

    /**
     * Returns the distinct sequence identifiers the framework resolved during the run.
     *
     * @return the identifiers, rendered as strings
     */
    public Set<String> distinctKeys() {
        return Set.copyOf(distinctKeys);
    }

    private void issue(WorkloadContext context, HistoryRecorder.ProcessRecorder recorder, int index) {
        String stream = "stream-" + (index % STREAMS);
        String eventId = stream + "-" + (index / STREAMS);
        HistoryRecorder.Invocation invocation =
                recorder.invoke(HistoryOps.TRANSFER, stream,
                                Map.of("kind", "tick", "eventId", eventId, "index", index));
        try {
            context.commandBus()
                   .dispatch(new GenericCommandMessage(new MessageType(Tick.class), new Tick(stream, eventId, index)),
                             null)
                   .orTimeout(COMMAND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                   .join();
            invocation.ok(Map.of("committed", true));
        } catch (CompletionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof TimeoutException) {
                invocation.indeterminate(cause.getClass().getSimpleName(), Map.of("committed", "unknown"));
            } else {
                invocation.fail(cause.getClass().getSimpleName(), Map.of("committed", false));
            }
        }
    }

    private MessageStream.Single<CommandResultMessage> handle(EventStore eventStore,
                                                              CommandMessage command,
                                                              ProcessingContext processingContext) {
        Tick tick = (Tick) Objects.requireNonNull(command.payload(), "The payload cannot be null.");
        EventStoreTransaction transaction = eventStore.transaction(processingContext);
        transaction.appendEvent(new GenericEventMessage(tick.eventId(),
                                                        new MessageType(Ticked.class),
                                                        new Ticked(tick.stream(), tick.index()),
                                                        Map.of(),
                                                        Instant.EPOCH));
        return MessageStream.just(new GenericCommandResultMessage(new MessageType("sequenced.ticked"), "ticked"));
    }

    private MessageStream.Empty<Message> project(HistoryRecorder.ProcessRecorder recorder, EventMessage event) {
        if (event.payload() instanceof Ticked ticked) {
            delivered.incrementAndGet();
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("eventId", event.identifier());
            value.put("index", ticked.index());
            String key = resolvedKeys.get(event.identifier());
            if (key != null) {
                value.put(HistoryOps.SEQUENCE_KEY, key);
            }
            recorder.invoke(HistoryOps.DELIVER, ticked.stream(), Map.copyOf(value)).ok(Map.of());
        }
        return MessageStream.empty();
    }

    /**
     * Records the sequence identifier the framework resolved, then hands the call on unchanged.
     * <p>
     * The processor asks the outermost component for an identifier both to pick the segment an event belongs to and to
     * chain same-identifier invocations, so this is the identifier the run was actually routed by. Wrapping is the
     * only way to observe it: recomputing the policy from the workload would answer a different question.
     *
     * @author Stefan Dragisic
     * @since 5.3.0
     */
    private final class SequenceRecordingComponent extends DelegatingEventHandlingComponent {

        private final HistoryRecorder.ProcessRecorder recorder;

        private SequenceRecordingComponent(EventHandlingComponent delegate,
                                           HistoryRecorder.ProcessRecorder recorder) {
            super(delegate);
            this.recorder = recorder;
        }

        @Override
        public Object sequenceIdentifierFor(EventMessage event, ProcessingContext context) {
            try {
                Object identifier = super.sequenceIdentifierFor(event, context);
                String rendered = String.valueOf(identifier);
                resolvedKeys.put(event.identifier(), rendered);
                distinctKeys.add(rendered);
                return identifier;
            } catch (RuntimeException e) {
                if (unresolved.incrementAndGet() <= FAILURE_SAMPLE_LIMIT) {
                    recorder.info(HistoryOps.SEQUENCE, event.identifier(),
                                  Map.of("arm", arm,
                                         "policy", policy.getClass().getSimpleName(),
                                         "error", e.getClass().getName()));
                }
                throw e;
            }
        }
    }

    /**
     * Append one event to one stream.
     *
     * @param stream  the stream the event belongs to, which is also its tag value
     * @param eventId the identifier the event will carry
     * @param index   the command's position in the run, so a reader can see the append order without the store
     * @author Stefan Dragisic
     * @since 5.3.0
     */
    public record Tick(String stream, String eventId, int index) {

    }

    /**
     * One event of a stream.
     *
     * @param stream the stream it belongs to
     * @param index  the position of the command that appended it
     * @author Stefan Dragisic
     * @since 5.3.0
     */
    public record Ticked(String stream, int index) {

    }
}
