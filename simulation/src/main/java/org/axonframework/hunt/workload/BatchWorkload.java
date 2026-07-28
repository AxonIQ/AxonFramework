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

import org.axonframework.eventsourcing.eventstore.ConsistencyMarker;
import org.axonframework.eventsourcing.eventstore.EventStore;
import org.axonframework.eventsourcing.eventstore.EventStoreTransaction;
import org.axonframework.eventsourcing.eventstore.SourcingCondition;
import org.axonframework.eventsourcing.eventstore.TagResolver;
import org.axonframework.hunt.history.HistoryOps;
import org.axonframework.hunt.history.HistoryRecorder;
import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.commandhandling.CommandResultMessage;
import org.axonframework.messaging.commandhandling.GenericCommandMessage;
import org.axonframework.messaging.commandhandling.GenericCommandResultMessage;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.EventHandlingComponent;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.GenericEventMessage;
import org.axonframework.messaging.eventhandling.SimpleEventHandlingComponent;
import org.axonframework.messaging.eventstreaming.EventCriteria;
import org.axonframework.messaging.eventstreaming.Tag;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A single writer publishing whole batches, optionally watched by a reader polling at maximum rate.
 * <p>
 * The workload exists for the two questions a ledger cannot answer. The first is what a transaction leaves behind
 * when it dies at each of the three phases the framework drives it through, which needs a batch whose identifiers are
 * known up front and a read side that records every delivery. The second is whether a batch becomes visible all at
 * once, which needs a reader reading the store while a batch is being written and a batch large enough for the window
 * to be reachable.
 * <p>
 * There is deliberately no conservation law here. A conservation oracle would be wrong for these arms: a failure
 * injected after the commit leaves events durably stored while the command that produced them reports failure, and a
 * checker folding only successful commands would report the difference as lost money rather than as the injection it
 * is.
 * <p>
 * Example usage:
 * <pre>{@code
 * Scenario scenario = Scenario.builder("partial_batch_never_visible_to_concurrent_reader", "...")
 *         .workload(BatchWorkload::wideBatchesUnderAPollingReader)
 *         .build();
 * }</pre>
 *
 * @author Stefan Dragisic
 * @since 5.3.0
 */
public final class BatchWorkload implements Workload {

    /**
     * The value key a poll's per-batch observation map is recorded under: batch identifier to the number of that
     * batch's events the reader could see in that one read.
     */
    public static final String OBSERVED = "observed";

    /**
     * The value key the number of events every batch holds is recorded under.
     */
    public static final String BATCH_SIZE = "batchSize";

    /**
     * The value key the number of reads a reader completed is recorded under.
     */
    public static final String POLLS = "polls";

    /**
     * The tag key every event this workload appends carries.
     */
    public static final String TOPIC_TAG = "topic";

    private static final String WRITER = "writer-0";
    private static final String READER = "reader-0";
    private static final int TOPICS = 4;
    private static final java.time.Duration COMMAND_TIMEOUT = java.time.Duration.ofSeconds(15);

    private final int batchSize;
    private final boolean polling;
    private final AtomicLong delivered = new AtomicLong();
    /**
     * Every identifier the projection has been handed, which is what quiescence is decided on.
     * <p>
     * Separate from the counter beside it on purpose: the counter answers whether the read side is still moving, which a
     * set cannot because a repeated delivery does not grow it, and the set answers whether the read side is complete,
     * which a count cannot because one batch's events are not adjacent in a store whose index comes from a sequence.
     */
    private final java.util.Set<String> deliveredIds = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final AtomicLong polls = new AtomicLong();
    private final AtomicBoolean readerStopped = new AtomicBoolean();

    private BatchWorkload(int batchSize, boolean polling) {
        this.batchSize = batchSize;
        this.polling = polling;
    }

    /**
     * Creates a writer publishing small batches, with no reader watching the store.
     * <p>
     * Small on purpose: the arms this shape serves are about what survives a failed transaction, and a batch of three
     * is enough to show a batch either landing whole or not at all while keeping every run cheap.
     *
     * @return the workload
     */
    public static BatchWorkload smallBatches() {
        return new BatchWorkload(3, false);
    }

    /**
     * Creates a writer publishing hundred-event batches with a reader reading the store as fast as it can.
     *
     * @return the workload
     */
    public static BatchWorkload wideBatchesUnderAPollingReader() {
        return new BatchWorkload(100, true);
    }

    @Override
    public String id() {
        return "batch";
    }

    @Override
    public TagResolver tagResolver() {
        return event -> event.payload() instanceof Published published
                ? Set.of(new Tag(TOPIC_TAG, published.topic()))
                : Set.of();
    }

    @Override
    public Map<String, String> describe(long seed, int commands) {
        Map<String, String> described = new LinkedHashMap<>();
        described.put("workload", id());
        described.put("commands", String.valueOf(commands));
        described.put(BATCH_SIZE, String.valueOf(batchSize));
        described.put("topics", String.valueOf(TOPICS));
        described.put("readers", polling ? "1" : "0");
        return Map.copyOf(described);
    }

    @Override
    public List<String> participants(long seed, int commands, org.axonframework.hunt.harness.DeterminismMode mode) {
        return polling ? List.of(WRITER, READER) : List.of(WRITER);
    }

    @Override
    public EventHandlingComponent install(WorkloadContext context) {
        EventStore eventStore = context.eventStore();
        context.world().commandBus()
               .subscribe(new QualifiedName(Publish.class),
                          (command, processingContext) -> handle(eventStore, command, processingContext));

        HistoryRecorder.ProcessRecorder projection = context.recorder().forProcess("projection", null);
        return SimpleEventHandlingComponent.create("batch-projection")
                                           .subscribe(new QualifiedName(Published.class),
                                                      (event, ctx) -> project(projection, event));
    }

    @Override
    public void run(WorkloadContext context) throws InterruptedException {
        Thread reader = polling ? startReader(context) : null;
        try {
            HistoryRecorder.ProcessRecorder recorder = context.recorder().forProcess(WRITER, null);
            int batches = Math.max(1, context.commands() / batchSize);
            for (int batch = 0; batch < batches && !context.deadline().expired(); batch++) {
                issue(context, recorder, batch);
            }
        } finally {
            readerStopped.set(true);
            if (reader != null) {
                reader.join(Math.max(1L, context.deadline().remaining().toMillis()));
            }
        }
    }

    @Override
    public long deliveredEvents(WorkloadContext context) {
        return delivered.get();
    }

    @Override
    public java.util.Set<String> deliveredEventIds(WorkloadContext context) {
        return deliveredIds;
    }

    @Override
    public void recordFinalState(WorkloadContext context) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("deliveredEvents", delivered.get());
        state.put(BATCH_SIZE, batchSize);
        state.put(POLLS, polls.get());
        context.recorder().forProcess("projection", null).info(HistoryOps.PROJECTION, null, Map.copyOf(state));
    }

    private Thread startReader(WorkloadContext context) {
        HistoryRecorder.ProcessRecorder recorder = context.recorder().forProcess(READER, null);
        Thread reader = new Thread(() -> readUntilStopped(context, recorder), READER);
        reader.setDaemon(true);
        reader.start();
        return reader;
    }

    /**
     * Reads the whole store, over and over, counting how much of each batch was visible in each single read.
     * <p>
     * A read is one traversal of a stream the store opened at one instant. Counting per read rather than
     * cumulatively is the entire point: a batch seen in part by one read and in full by the next is exactly the
     * observation the arm exists to make.
     */
    private void readUntilStopped(WorkloadContext context, HistoryRecorder.ProcessRecorder recorder) {
        while (!readerStopped.get() && !context.deadline().expired()) {
            Map<String, Integer> perBatch = new TreeMap<>();
            MessageStream<EventMessage> stream = context.world().store()
                                                        .source(SourcingCondition.conditionFor(
                                                                EventCriteria.havingAnyTag()), null);
            try {
                for (var entry = stream.next(); entry.isPresent(); entry = stream.next()) {
                    if (entry.get().getResource(ConsistencyMarker.RESOURCE_KEY) != null) {
                        continue;
                    }
                    if (entry.get().message().payload() instanceof Published published) {
                        perBatch.merge(published.batch(), 1, Integer::sum);
                    }
                }
            } finally {
                stream.close();
            }
            polls.incrementAndGet();
            Map<String, Integer> torn = new TreeMap<>();
            perBatch.forEach((batch, seen) -> {
                if (seen < batchSize) {
                    torn.put(batch, seen);
                }
            });
            if (!torn.isEmpty()) {
                recorder.info(HistoryOps.POLL, null, Map.of(OBSERVED, torn, BATCH_SIZE, batchSize));
            }
        }
    }

    private void issue(WorkloadContext context, HistoryRecorder.ProcessRecorder recorder, int batch) {
        String topic = "topic-" + (batch % TOPICS);
        String batchId = "b" + batch;
        List<String> eventIds = new ArrayList<>(batchSize);
        for (int index = 0; index < batchSize; index++) {
            eventIds.add(batchId + "-e" + index);
        }
        HistoryRecorder.Invocation invocation =
                recorder.invoke(HistoryOps.TRANSFER, topic,
                                Map.of("kind", "publish", "batch", batchId, "eventIds", eventIds));
        try {
            context.commandBus()
                   .dispatch(new GenericCommandMessage(new MessageType(Publish.class),
                                                       new Publish(topic, batchId, eventIds)), null)
                   .orTimeout(COMMAND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                   .join();
            invocation.ok(Map.of("committed", true));
        } catch (CompletionException e) {
            Throwable cause = rootCause(e);
            if (cause instanceof TimeoutException) {
                invocation.indeterminate(cause.getClass().getSimpleName(), Map.of("committed", "unknown"));
            } else {
                invocation.fail(cause.getClass().getSimpleName(), Map.of("committed", false));
            }
        }
    }

    private static Throwable rootCause(Throwable failure) {
        Throwable cause = failure;
        while ((cause instanceof CompletionException || cause instanceof java.util.concurrent.ExecutionException)
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    private MessageStream.Single<CommandResultMessage> handle(EventStore eventStore,
                                                              CommandMessage command,
                                                              ProcessingContext processingContext) {
        Publish publish = (Publish) Objects.requireNonNull(command.payload(), "The payload cannot be null.");
        EventStoreTransaction transaction = eventStore.transaction(processingContext);
        // No sourcing and no override: the batch is offered unconditionally, so nothing but an injected failure can
        // stop it and the arm measures the phase rather than the conflict check.
        for (int index = 0; index < publish.eventIds().size(); index++) {
            transaction.appendEvent(new GenericEventMessage(publish.eventIds().get(index),
                                                            new MessageType(Published.class),
                                                            new Published(publish.topic(), publish.batch(), index),
                                                            Map.of(),
                                                            Instant.EPOCH));
        }
        return MessageStream.just(new GenericCommandResultMessage(new MessageType("batch.published"), "published"));
    }

    private MessageStream.Empty<org.axonframework.messaging.core.Message> project(
            HistoryRecorder.ProcessRecorder recorder, EventMessage event) {
        if (event.payload() instanceof Published published) {
            delivered.incrementAndGet();
            deliveredIds.add(event.identifier());
            recorder.invoke(HistoryOps.DELIVER, published.topic(),
                            Map.of("eventId", event.identifier(),
                                   "batch", published.batch(),
                                   "index", published.index()))
                    .ok(Map.of());
        }
        return MessageStream.empty();
    }

    /**
     * Publish one whole batch of events under one topic, in one transaction.
     *
     * @param topic    the tag every event in the batch carries
     * @param batch    the batch's identifier, carried by every event so a reader can tell them apart
     * @param eventIds the identifiers the batch's events will carry, in offer order
     * @author Stefan Dragisic
     * @since 5.3.0
     */
    public record Publish(String topic, String batch, List<String> eventIds) {

    }

    /**
     * One event of a published batch.
     *
     * @param topic the topic it was published under
     * @param batch the batch it belongs to
     * @param index its position within that batch, counted from zero
     * @author Stefan Dragisic
     * @since 5.3.0
     */
    public record Published(String topic, String batch, int index) {

    }
}
