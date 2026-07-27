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

package org.axonframework.hunt.harness;

import org.axonframework.common.infra.ComponentDescriptor;
import org.axonframework.eventsourcing.eventstore.AppendCondition;
import org.axonframework.eventsourcing.eventstore.AppendEventsTransactionRejectedException;
import org.axonframework.eventsourcing.eventstore.ConsistencyMarker;
import org.axonframework.eventsourcing.eventstore.EventStorageEngine;
import org.axonframework.eventsourcing.eventstore.SourcingCondition;
import org.axonframework.eventsourcing.eventstore.TaggedEventMessage;
import org.axonframework.hunt.fault.AppendAttempt;
import org.axonframework.hunt.fault.Buggify;
import org.axonframework.hunt.fault.CommitAction;
import org.axonframework.hunt.fault.StoreHook;
import org.axonframework.hunt.history.HistoryOps;
import org.axonframework.hunt.history.HistoryRecorder;
import org.axonframework.hunt.model.DcbHistoryCodec;
import org.axonframework.hunt.model.ModelAppendCondition;
import org.axonframework.hunt.model.ModelEvent;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventstreaming.StreamingCondition;
import org.axonframework.messaging.eventhandling.processing.streaming.token.TrackingToken;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The storage engine every hunt run drives, wrapping a real one.
 * <p>
 * It does two jobs and nothing else. It <em>records</em> the append the framework actually asked for, which is not the
 * append the workload thought it was asking for: the condition is derived from whatever sourcing preceded it, so the
 * derived condition is the only thing a conformance oracle can be run against. And it <em>lets a fault interfere</em>,
 * through hooks that faults install, so that latency, refusal, loss, duplication and truncation are expressed as
 * classes rather than as edits to this one.
 * <p>
 * The wrapped engine is untouched, and so is the rest of the framework. Everything this class adds sits strictly
 * between the framework and the store.
 * <p>
 * Example usage:
 * <pre>{@code
 * ControllableEventStorageEngine store =
 *         new ControllableEventStorageEngine(new InMemoryEventStorageEngine(), recorder, Buggify.inert());
 * store.installHook(hook);
 * EventStore eventStore = new StorageEngineBackedEventStore(store, new SimpleEventBus(), tagResolver);
 * }</pre>
 *
 * @author Stefan Dragisic
 * @since 5.3.0
 */
public final class ControllableEventStorageEngine implements EventStorageEngine {

    /**
     * The error a rejected append is recorded under when the store's own consistency check refused it.
     * <p>
     * A checker replaying against the reference model treats only this error as a protocol rejection; any other error
     * means the append failed for a reason the model knows nothing about.
     */
    public static final String CONSISTENCY_REJECTION = AppendEventsTransactionRejectedException.class.getSimpleName();

    private final EventStorageEngine delegate;
    private final HistoryRecorder recorder;
    private final Buggify buggify;
    private final List<StoreHook> hooks = new CopyOnWriteArrayList<>();
    private final Map<String, HistoryRecorder.ProcessRecorder> recorders = new ConcurrentHashMap<>();
    private final AtomicLong appendSequence = new AtomicLong();
    private final AtomicLong perturbations = new AtomicLong();
    private final AtomicLong storedEvents = new AtomicLong();

    /**
     * Wraps the given engine.
     *
     * @param delegate the real storage engine every call is ultimately made against
     * @param recorder the recorder every append, commit and rollback is written to
     * @param buggify  the scheduling-bias points to reach around the commit boundary
     */
    public ControllableEventStorageEngine(EventStorageEngine delegate,
                                          HistoryRecorder recorder,
                                          Buggify buggify) {
        this.delegate = Objects.requireNonNull(delegate, "The delegate cannot be null.");
        this.recorder = Objects.requireNonNull(recorder, "The recorder cannot be null.");
        this.buggify = Objects.requireNonNull(buggify, "The buggify cannot be null.");
    }

    /**
     * Installs a hook consulted around every append and commit.
     *
     * @param hook the hook to install
     */
    public void installHook(StoreHook hook) {
        hooks.add(Objects.requireNonNull(hook, "The hook cannot be null."));
    }

    /**
     * Removes a previously installed hook.
     *
     * @param hook the hook to remove
     */
    public void removeHook(StoreHook hook) {
        hooks.remove(hook);
    }

    /**
     * Returns how many commits were made to store something other than what was offered.
     * <p>
     * A run with a positive count cannot be judged against the reference model, because the model faithfully
     * reproduces what the workload asked for and the store deliberately does not hold that.
     *
     * @return the number of perturbed commits
     */
    public long perturbations() {
        return perturbations.get();
    }

    /**
     * Returns how many events actually reached the wrapped store.
     * <p>
     * This is what the read side has to catch up with, and it is deliberately not the number of events the workload
     * offered: a vanished commit offered events that nothing will ever deliver, and a duplicated one delivered more
     * than were offered.
     *
     * @return the number of events written to the wrapped engine
     */
    public long storedEvents() {
        return storedEvents.get();
    }

    @Override
    public CompletableFuture<AppendTransaction<?>> appendEvents(AppendCondition condition,
                                                                @Nullable ProcessingContext processingContext,
                                                                List<TaggedEventMessage<?>> events) {
        String participant = Thread.currentThread().getName();
        ModelAppendCondition modelCondition = AxonModelCodec.toModelCondition(condition);
        List<ModelEvent> modelEvents = AxonModelCodec.toModelEvents(events);
        AppendAttempt attempt = new AppendAttempt(participant,
                                                  modelEvents.stream().map(ModelEvent::id).toList(),
                                                  appendSequence.getAndIncrement());

        HistoryRecorder.Invocation invocation =
                recorderFor(participant).invoke(HistoryOps.APPEND,
                                                null,
                                                DcbHistoryCodec.encodeAppend(modelCondition, modelEvents));
        try {
            for (StoreHook hook : hooks) {
                hook.beforeAppend(attempt);
            }
        } catch (RuntimeException e) {
            invocation.fail(e.getClass().getSimpleName(), Map.of("phase", "before-append"));
            return CompletableFuture.failedFuture(e);
        }
        buggify.fire(Buggify.BEFORE_APPEND);

        return delegate.appendEvents(condition, processingContext, events)
                       .handle((transaction, failure) -> {
                           if (failure != null) {
                               invocation.fail(rootCauseName(failure), Map.of("phase", "append"));
                               throw new java.util.concurrent.CompletionException(failure);
                           }
                           return (AppendTransaction<?>) new InterferingTransaction(transaction,
                                                                                    condition,
                                                                                    events,
                                                                                    attempt,
                                                                                    invocation,
                                                                                    participant);
                       });
    }

    @Override
    public MessageStream<EventMessage> source(SourcingCondition condition,
                                              @Nullable ProcessingContext processingContext) {
        return delegate.source(condition, processingContext);
    }

    @Override
    public MessageStream<EventMessage> stream(StreamingCondition condition) {
        return delegate.stream(condition);
    }

    @Override
    public CompletableFuture<TrackingToken> firstToken() {
        return delegate.firstToken();
    }

    @Override
    public CompletableFuture<TrackingToken> latestToken() {
        return delegate.latestToken();
    }

    @Override
    public CompletableFuture<TrackingToken> tokenAt(Instant at) {
        return delegate.tokenAt(at);
    }

    @Override
    public void describeTo(ComponentDescriptor descriptor) {
        descriptor.describeProperty("delegate", delegate);
        descriptor.describeProperty("installedHooks", hooks.size());
        descriptor.describeProperty("perturbations", perturbations.get());
    }

    private HistoryRecorder.ProcessRecorder recorderFor(String participant) {
        return recorders.computeIfAbsent(participant, name -> recorder.forProcess(name, null));
    }

    private static String rootCauseName(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null && cause instanceof java.util.concurrent.CompletionException) {
            cause = cause.getCause();
        }
        return cause.getClass().getSimpleName();
    }

    private CommitAction decide(AppendAttempt attempt) {
        CommitAction decided = CommitAction.proceed();
        for (StoreHook hook : hooks) {
            CommitAction action = hook.onCommit(attempt);
            if (action.kind() != CommitAction.Kind.PROCEED) {
                decided = action;
            }
        }
        return decided;
    }

    /**
     * The append transaction the framework drives, with the faults' decision applied at commit time.
     *
     * @author Stefan Dragisic
     * @since 5.3.0
     */
    private final class InterferingTransaction implements AppendTransaction<Object> {

        private final AppendTransaction<Object> delegateTransaction;
        private final AppendCondition condition;
        private final List<TaggedEventMessage<?>> events;
        private final AppendAttempt attempt;
        private final HistoryRecorder.Invocation invocation;
        private final String participant;

        @SuppressWarnings("unchecked")
        private InterferingTransaction(AppendTransaction<?> delegateTransaction,
                                       AppendCondition condition,
                                       List<TaggedEventMessage<?>> events,
                                       AppendAttempt attempt,
                                       HistoryRecorder.Invocation invocation,
                                       String participant) {
            this.delegateTransaction = (AppendTransaction<Object>) delegateTransaction;
            this.condition = condition;
            this.events = events;
            this.attempt = attempt;
            this.invocation = invocation;
            this.participant = participant;
        }

        @Override
        public CompletableFuture<Object> commit() {
            buggify.fire(Buggify.BEFORE_COMMIT);
            CommitAction action = decide(attempt);
            if (action.perturbsStoreContents()) {
                perturbations.incrementAndGet();
            }
            return switch (action.kind()) {
                case PROCEED -> commitAsOffered();
                case REJECT -> refuse();
                case VANISH -> vanish();
                case DUPLICATE -> duplicate();
                case PREFIX -> storePrefix(action.keepCount());
                case BYPASS_CONDITION -> storeWithoutCondition();
            };
        }

        @Override
        public void rollback() {
            delegateTransaction.rollback();
            recorderFor(participant).invoke(HistoryOps.ROLLBACK,
                                            null,
                                            Map.of(DcbHistoryCodec.EVENT_IDS, attempt.eventIds()))
                                    .ok(Map.of());
        }

        @Override
        public CompletableFuture<ConsistencyMarker> afterCommit(@Nullable Object commitResult) {
            if (commitResult == null) {
                return CompletableFuture.completedFuture(ConsistencyMarker.ORIGIN);
            }
            return delegateTransaction.afterCommit(commitResult);
        }

        private CompletableFuture<Object> commitAsOffered() {
            HistoryRecorder.Invocation commit = beginCommit(attempt.eventIds());
            return delegateTransaction.commit().handle((result, failure) -> {
                if (failure != null) {
                    commit.fail(rootCauseName(failure), Map.of());
                    invocation.fail(rootCauseName(failure), Map.of("phase", "commit"));
                    throw new java.util.concurrent.CompletionException(failure);
                }
                storedEvents.addAndGet(attempt.batchSize());
                commit.ok(Map.of());
                invocation.ok(Map.of(DcbHistoryCodec.EVENT_IDS, attempt.eventIds()));
                buggify.fire(Buggify.AFTER_COMMIT);
                return result;
            });
        }

        private CompletableFuture<Object> refuse() {
            delegateTransaction.rollback();
            invocation.fail(InjectedStoreFailureException.class.getSimpleName(), Map.of("phase", "commit"));
            return CompletableFuture.failedFuture(
                    new InjectedStoreFailureException("The commit of [" + attempt.describe()
                                                              + "] was refused by an injected fault."));
        }

        private CompletableFuture<Object> vanish() {
            delegateTransaction.rollback();
            beginCommit(List.of()).ok(Map.of());
            invocation.ok(Map.of(DcbHistoryCodec.EVENT_IDS, attempt.eventIds()));
            note("vanish", attempt.eventIds(), List.of());
            return CompletableFuture.completedFuture(null);
        }

        private CompletableFuture<Object> duplicate() {
            return commitAsOffered().thenCompose(result -> {
                HistoryRecorder.Invocation second = beginCommit(attempt.eventIds());
                return reAppend(events).thenApply(ignored -> {
                    second.ok(Map.of());
                    note("duplicate", attempt.eventIds(), attempt.eventIds());
                    return result;
                });
            });
        }

        private CompletableFuture<Object> storePrefix(int keepCount) {
            delegateTransaction.rollback();
            List<TaggedEventMessage<?>> kept = events.subList(0, Math.min(keepCount, events.size()));
            List<String> keptIds = AxonModelCodec.toModelEvents(kept).stream().map(ModelEvent::id).toList();
            HistoryRecorder.Invocation commit = beginCommit(keptIds);
            return reAppend(kept).thenApply(ignored -> {
                commit.ok(Map.of());
                invocation.ok(Map.of(DcbHistoryCodec.EVENT_IDS, attempt.eventIds()));
                note("prefix", attempt.eventIds(), keptIds);
                return (Object) null;
            });
        }

        private CompletableFuture<Object> storeWithoutCondition() {
            delegateTransaction.rollback();
            HistoryRecorder.Invocation commit = beginCommit(attempt.eventIds());
            return reAppend(events).thenApply(ignored -> {
                commit.ok(Map.of());
                invocation.ok(Map.of(DcbHistoryCodec.EVENT_IDS, attempt.eventIds()));
                return (Object) null;
            });
        }

        private CompletableFuture<Void> reAppend(List<TaggedEventMessage<?>> batch) {
            if (batch.isEmpty()) {
                return CompletableFuture.completedFuture(null);
            }
            return delegate.appendEvents(AppendCondition.none(), null, batch)
                           .thenCompose(transaction -> transaction.commit()
                                                                  .thenRun(() -> storedEvents.addAndGet(batch.size())));
        }

        /**
         * Records that a commit is starting, before anything can become visible.
         * <p>
         * The order matters: a consumer can observe an event the instant the store publishes it, which is before the
         * call that published it has returned. Recording the commit only afterwards would place a legitimate delivery
         * before its own commit in the history and turn every fast consumer into a visibility violation.
         */
        private HistoryRecorder.Invocation beginCommit(List<String> visibleEventIds) {
            return recorderFor(participant).invoke(HistoryOps.COMMIT,
                                                   null,
                                                   Map.of(DcbHistoryCodec.EVENT_IDS, visibleEventIds));
        }

        private void note(String interference, List<String> offered, List<String> stored) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("interference", interference);
            value.put("offered", offered);
            value.put("stored", stored);
            value.put("condition", condition.toString());
            recorderFor(participant).info(HistoryOps.STORE_PERTURBED, null, Map.copyOf(value));
        }
    }
}
