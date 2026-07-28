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

import org.axonframework.eventsourcing.eventstore.AppendCondition;
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
import org.axonframework.messaging.core.sequencing.SequencingPolicy;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.EventHandlingComponent;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.GenericEventMessage;
import org.axonframework.messaging.eventhandling.SimpleEventHandlingComponent;
import org.axonframework.messaging.eventhandling.replay.ResetHandler;
import org.axonframework.messaging.eventhandling.processing.streaming.segmenting.Segment;
import org.axonframework.messaging.eventhandling.processing.streaming.token.ReplayToken;
import org.axonframework.messaging.eventhandling.processing.streaming.token.TrackingToken;
import org.axonframework.messaging.eventstreaming.EventCriteria;
import org.axonframework.messaging.eventstreaming.Tag;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Accounts, transfers conditioned on a Dynamic Consistency Boundary, and a balance projection.
 * <p>
 * The point of the ledger is its conservation law. Money is neither created nor destroyed, so the sum of every
 * balance is fixed for the whole run. One arithmetic identity therefore catches a lost event, a doubled event, a torn
 * batch and a bypassed conflict check, without the suite having to know in advance which of those it is looking for.
 * That is a far stronger oracle than any number of assertions about the mechanism.
 * <p>
 * The workload issues three kinds of command, chosen so that each exercises a different shape of append condition
 * while all three leave the ledger's total untouched:
 * <ul>
 *     <li><b>transfer</b> sources both accounts, checks the funds, and appends a withdrawal and a deposit. The
 *     framework derives the append condition from that sourcing, which is the condition the conflict check is really
 *     made against.</li>
 *     <li><b>seize</b> overrides the condition to claim one account's whole history, so it conflicts with anything
 *     already stored under that account. It moves an amount out of an account and straight back in, so it changes no
 *     balance.</li>
 *     <li><b>rebalance</b> appends without sourcing at all, which is how the framework produces an unconditional
 *     append. It is the control arm: an unconditional append that is ever rejected is a defect.</li>
 * </ul>
 * The read side is a real streaming processor over a real event store, so the projection is built the way an
 * application's would be, and comparing it against a fold of what the run committed is a genuine end-to-end check
 * rather than a restatement of the write path.
 *
 * @author Stefan Dragisic
 * @since 5.3.0
 */
public final class LedgerWorkload implements Workload {

    /**
     * The balance every account starts with. No genesis events are appended: an empty account is simply worth this.
     */
    public static final long OPENING_BALANCE = 1_000L;

    /**
     * The value key the ledger's opening total is recorded under.
     */
    public static final String OPENING_TOTAL = "openingTotal";

    /**
     * The value key the projection's balances are recorded under.
     */
    public static final String BALANCES = "balances";

    /**
     * The value key a transfer's source account is recorded under.
     */
    public static final String FROM = "from";

    /**
     * The value key a transfer's target account is recorded under.
     */
    public static final String TO = "to";

    /**
     * The value key a transfer's amount is recorded under.
     */
    public static final String AMOUNT = "amount";

    /**
     * The tag key every ledger event carries.
     */
    public static final String ACCOUNT_TAG = "account";

    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(15);
    private static final long MAX_AMOUNT = 50L;
    private static final int TRANSFER_SHARE = 70;
    private static final int SEIZE_SHARE = 90;

    private final boolean forceHotKey;
    private final boolean sequencePerAccount;
    private final boolean idempotentProjection;
    private final Set<String> applied = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Map<Long, SwarmShape> shapes = new ConcurrentHashMap<>();
    private final Map<String, Long> balances = new ConcurrentHashMap<>();
    private final Map<String, HistoryRecorder.ProcessRecorder> projectionRecorders = new ConcurrentHashMap<>();
    private final AtomicLong delivered = new AtomicLong();

    private LedgerWorkload(boolean forceHotKey, boolean sequencePerAccount, boolean idempotentProjection) {
        this.forceHotKey = forceHotKey;
        this.sequencePerAccount = sequencePerAccount;
        this.idempotentProjection = idempotentProjection;
    }

    /**
     * Creates a ledger whose access distribution is pinned to the hot-key shape.
     * <p>
     * Used by scenarios whose claim is about the conflict path, where a uniform arm would spend most of its budget
     * never producing a conflict at all.
     *
     * @return the workload
     */
    public static LedgerWorkload hotKey() {
        return new LedgerWorkload(true, false, false);
    }

    /**
     * Creates a hot-key ledger whose events are sequenced by the account they touch.
     * <p>
     * <b>Any multi-segment scenario needs this, and the reason is easy to miss.</b> The processor picks an event's
     * segment by hashing the sequence identifier the handling component resolves, and the sequencing policy the
     * framework wires by default resolves its key from a legacy aggregate-identifier resource that no store speaking
     * the Dynamic Consistency Boundary protocol ever populates. Its fallback then gives every event in the run the
     * same identifier, one identifier hashes to one segment, and a processor configured with sixteen segments has one
     * segment doing all the work and fifteen doing none. Measured on this suite's own runs: one distinct sequence
     * identifier for a hundred and eighty events across six independent streams. Keying on the account restores the
     * spread that the whole point of several nodes depends on.
     * <p>
     * The policy always resolves something, deliberately. The component unwraps the policy's optional with
     * {@code get()}, so a policy that ever answers nothing throws once per event and the read side silently delivers
     * nothing at all; an event that is not a ledger event therefore falls back to its own identifier rather than to
     * an empty answer.
     *
     * @return the workload
     */
    public static LedgerWorkload sequencedPerAccount() {
        return new LedgerWorkload(true, true, false);
    }

    /**
     * Creates a per-account sequenced ledger whose projection applies each event at most once.
     * <p>
     * <b>Which deployment a scenario is modelling decides whether this is the right ledger.</b> Where the token store
     * and the read model are separate resources the framework's guarantee is at-least-once and its documentation says
     * plainly that handlers must be idempotent; a projection that simply adds the amount again when a stolen claim
     * causes a redelivery is a projection nobody would deploy, and the conservation law would report the framework's own
     * documented behaviour as money appearing out of nowhere. Any arm that deliberately makes a claim change hands
     * therefore needs this ledger.
     * <p>
     * <b>What it costs, stated rather than glossed over.</b> The sum of the balances no longer notices a repeated
     * delivery, so this ledger is a weaker oracle than {@link #sequencedPerAccount()} by exactly that much. It still
     * notices a lost event, a torn batch, a doubled <em>append</em> and a bypassed conflict check, and the repeated
     * deliveries it absorbs are still counted and reported by the delivery oracle -- which is where a redelivery belongs,
     * because that oracle knows whether the run was entitled to one. Arms with no handover keep the sharper ledger.
     *
     * @return the workload
     */
    public static LedgerWorkload sequencedPerAccountIdempotent() {
        return new LedgerWorkload(true, true, true);
    }

    /**
     * Creates a ledger whose access distribution, like every other knob, is chosen by the seed.
     *
     * @return the workload
     */
    public static LedgerWorkload seedShaped() {
        return new LedgerWorkload(false, false, false);
    }

    @Override
    public String id() {
        return "ledger";
    }

    @Override
    public TagResolver tagResolver() {
        return event -> event.payload() instanceof LedgerEvent ledgerEvent
                ? Set.of(new Tag(ACCOUNT_TAG, ledgerEvent.account()))
                : Set.of();
    }

    @Override
    public Map<String, String> describe(long seed, int commands) {
        Map<String, String> described = new LinkedHashMap<>(shape(seed).describe());
        described.put("workload", id());
        described.put("commands", String.valueOf(commands));
        described.put("openingBalance", String.valueOf(OPENING_BALANCE));
        described.put("opMix", "transfer=" + TRANSFER_SHARE + "%,seize=" + (SEIZE_SHARE - TRANSFER_SHARE)
                + "%,rebalance=" + (100 - SEIZE_SHARE) + "%");
        described.put("sequencingPolicy", sequencePerAccount ? "per-account" : "framework-default");
        described.put("projectionIdempotent", String.valueOf(idempotentProjection));
        return Map.copyOf(described);
    }

    @Override
    public List<String> participants(long seed,
                                     int commands,
                                     org.axonframework.hunt.harness.DeterminismMode mode) {
        int count = writerCount(shape(seed), mode);
        List<String> writers = new ArrayList<>(count);
        for (int writer = 0; writer < count; writer++) {
            writers.add(writerName(writer));
        }
        return List.copyOf(writers);
    }

    /**
     * Returns how many writer threads a run actually uses.
     * <p>
     * The shape asks for as many as the seed chose, but a run that pinned its scheduling down asked for one writer,
     * and honouring the shape there would leave the most obvious source of nondeterminism running.
     */
    private static int writerCount(SwarmShape shape, org.axonframework.hunt.harness.DeterminismMode mode) {
        return mode == org.axonframework.hunt.harness.DeterminismMode.SINGLE_THREADED ? 1 : shape.writers();
    }

    @Override
    public EventHandlingComponent install(WorkloadContext context) {
        EventStore eventStore = context.eventStore();
        context.world().commandBus()
               .subscribe(new QualifiedName(Transfer.class),
                          (command, processingContext) -> handleTransfer(eventStore, command, processingContext))
               .subscribe(new QualifiedName(Seize.class),
                          (command, processingContext) -> handleSeize(eventStore, command, processingContext))
               .subscribe(new QualifiedName(Rebalance.class),
                          (command, processingContext) -> handleRebalance(eventStore, command, processingContext));

        SimpleEventHandlingComponent component =
                sequencePerAccount
                        ? SimpleEventHandlingComponent.create("ledger-projection", PER_ACCOUNT_POLICY)
                        : SimpleEventHandlingComponent.create("ledger-projection");
        return component.subscribe(new QualifiedName(MoneyWithdrawn.class),
                                   (event, ctx) -> project(context, event, ctx))
                        .subscribe(new QualifiedName(MoneyDeposited.class),
                                   (event, ctx) -> project(context, event, ctx))
                        // A projection that is replayed must start from nothing, or the replay adds a second copy of
                        // every transfer to the balances it already holds and the conservation law reports the replay
                        // itself as money appearing out of nowhere. Clearing on reset is what a real projection does,
                        // and it is what makes "the projection equals the fold of the full history" a statement about
                        // the framework rather than about this workload's arithmetic.
                        .subscribe((ResetHandler) (resetContext, ctx) -> {
                            balances.clear();
                            applied.clear();
                            delivered.set(0L);
                            return MessageStream.empty();
                        });
    }

    /**
     * Sequences by the account an event touches, and never answers nothing.
     * <p>
     * Two accounts are independent, so their events may be handled in parallel and may land in different segments,
     * which is what makes several nodes worth having. The fallback to the event's own identifier is not defensive
     * tidiness: the handling component unwraps this optional with {@code get()}, so an empty answer throws on every
     * event and the read side stops dead without saying so.
     */
    private static final SequencingPolicy<EventMessage> PER_ACCOUNT_POLICY =
            (event, context) -> Optional.of(ledgerEventOf(event).map(LedgerEvent::account)
                                                           .orElseGet(event::identifier));

    @Override
    public void run(WorkloadContext context) throws InterruptedException {
        SwarmShape shape = shape(context.seed());
        int writerCount = writerCount(shape, context.determinism());
        int perWriter = Math.max(1, context.commands() / writerCount);
        List<Thread> writers = new ArrayList<>(writerCount);
        for (int writer = 0; writer < writerCount; writer++) {
            int index = writer;
            Thread thread = new Thread(() -> drive(context, shape, index, perWriter), writerName(writer));
            thread.setDaemon(true);
            writers.add(thread);
            thread.start();
        }
        for (Thread writer : writers) {
            writer.join(Math.max(1L, context.deadline().remaining().toMillis()));
        }
    }

    @Override
    public boolean quiesced(WorkloadContext context) {
        return delivered.get() >= context.world().store().storedEvents();
    }

    @Override
    public void recordFinalState(WorkloadContext context) {
        SwarmShape shape = shape(context.seed());
        Map<String, Object> projection = new LinkedHashMap<>();
        Map<String, Object> rendered = new LinkedHashMap<>();
        for (int account = 0; account < shape.accounts(); account++) {
            String name = accountName(account);
            rendered.put(name, OPENING_BALANCE + balances.getOrDefault(name, 0L));
        }
        projection.put(BALANCES, Map.copyOf(rendered));
        projection.put(OPENING_TOTAL, (long) shape.accounts() * OPENING_BALANCE);
        projection.put("deliveredEvents", delivered.get());
        context.recorder().forProcess("projection", null)
               .info(HistoryOps.PROJECTION, null, Map.copyOf(projection));
    }

    private SwarmShape shape(long seed) {
        return shapes.computeIfAbsent(seed, key -> forceHotKey ? SwarmShape.zipf(key) : SwarmShape.of(key));
    }

    private void drive(WorkloadContext context, SwarmShape shape, int writerIndex, int perWriter) {
        String participant = writerName(writerIndex);
        HistoryRecorder.ProcessRecorder recorder = context.recorder().forProcess(participant, null);
        Random random = new Random(context.seed() * 1_000_003L + writerIndex);
        int issued = 0;
        try {
            while (issued < perWriter && !context.deadline().expired()) {
                context.pauses().checkpoint(participant);
                int batch = shape.pickBatch(random);
                for (int inBatch = 0; inBatch < batch && issued < perWriter; inBatch++, issued++) {
                    issue(context, shape, recorder, participant, random, issued);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void issue(WorkloadContext context,
                       SwarmShape shape,
                       HistoryRecorder.ProcessRecorder recorder,
                       String participant,
                       Random random,
                       int sequence) {
        String from = accountName(shape.pickAccount(random));
        String to = accountName(shape.pickAccount(random));
        long amount = 1 + random.nextInt((int) MAX_AMOUNT);
        String withdrawId = participant + "-" + sequence + "-w";
        String depositId = participant + "-" + sequence + "-d";
        int choice = random.nextInt(100);

        CommandMessage command;
        String kind;
        if (choice < TRANSFER_SHARE && !from.equals(to)) {
            kind = "transfer";
            command = new GenericCommandMessage(new MessageType(Transfer.class),
                                                new Transfer(from, to, amount, withdrawId, depositId));
        } else if (choice < SEIZE_SHARE) {
            kind = "seize";
            to = from;
            command = new GenericCommandMessage(new MessageType(Seize.class),
                                                new Seize(from, amount, withdrawId, depositId));
        } else {
            kind = "rebalance";
            to = from;
            command = new GenericCommandMessage(new MessageType(Rebalance.class),
                                                new Rebalance(from, amount, withdrawId, depositId));
        }

        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("kind", kind);
        arguments.put(FROM, from);
        arguments.put(TO, to);
        arguments.put(AMOUNT, amount);
        arguments.put("withdrawEventId", withdrawId);
        arguments.put("depositEventId", depositId);
        HistoryRecorder.Invocation invocation =
                recorder.invoke(HistoryOps.TRANSFER, from, Map.copyOf(arguments));
        try {
            context.commandBus()
                   .dispatch(command, null)
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

    private MessageStream.Single<CommandResultMessage> handleTransfer(EventStore eventStore,
                                                                      CommandMessage command,
                                                                      ProcessingContext processingContext) {
        Transfer transfer = (Transfer) Objects.requireNonNull(command.payload(), "The payload cannot be null.");
        EventStoreTransaction transaction = eventStore.transaction(processingContext);
        EventCriteria criteria = EventCriteria.either(
                EventCriteria.havingTags(new Tag(ACCOUNT_TAG, transfer.from())),
                EventCriteria.havingTags(new Tag(ACCOUNT_TAG, transfer.to())));

        CompletableFuture<CommandResultMessage> result =
                fold(transaction, criteria).thenApply(sourced -> {
                    long available = OPENING_BALANCE + sourced.getOrDefault(transfer.from(), 0L);
                    if (available < transfer.amount()) {
                        throw new InsufficientFundsException(
                                "Account [" + transfer.from() + "] holds " + available + " but " + transfer.amount()
                                        + " was requested.");
                    }
                    transaction.appendEvent(event(transfer.withdrawEventId(),
                                                  new MoneyWithdrawn(transfer.from(), transfer.amount())));
                    transaction.appendEvent(event(transfer.depositEventId(),
                                                  new MoneyDeposited(transfer.to(), transfer.amount())));
                    return accepted();
                });
        return MessageStream.fromFuture(result);
    }

    private MessageStream.Single<CommandResultMessage> handleSeize(EventStore eventStore,
                                                                   CommandMessage command,
                                                                   ProcessingContext processingContext) {
        Seize seize = (Seize) Objects.requireNonNull(command.payload(), "The payload cannot be null.");
        EventStoreTransaction transaction = eventStore.transaction(processingContext);
        transaction.appendEvent(event(seize.withdrawEventId(), new MoneyWithdrawn(seize.account(), seize.amount())));
        transaction.appendEvent(event(seize.depositEventId(), new MoneyDeposited(seize.account(), seize.amount())));
        // Anchored at the origin, so every event already stored under this account is a conflict.
        transaction.overrideAppendCondition(ignored -> AppendCondition.withCriteria(
                EventCriteria.havingTags(new Tag(ACCOUNT_TAG, seize.account()))));
        return MessageStream.just(accepted());
    }

    private MessageStream.Single<CommandResultMessage> handleRebalance(EventStore eventStore,
                                                                       CommandMessage command,
                                                                       ProcessingContext processingContext) {
        Rebalance rebalance = (Rebalance) Objects.requireNonNull(command.payload(), "The payload cannot be null.");
        EventStoreTransaction transaction = eventStore.transaction(processingContext);
        // No sourcing and no override, which is how the framework produces an unconditional append.
        transaction.appendEvent(event(rebalance.withdrawEventId(),
                                      new MoneyWithdrawn(rebalance.account(), rebalance.amount())));
        transaction.appendEvent(event(rebalance.depositEventId(),
                                      new MoneyDeposited(rebalance.account(), rebalance.amount())));
        return MessageStream.just(accepted());
    }

    private static CompletableFuture<Map<String, Long>> fold(EventStoreTransaction transaction,
                                                             EventCriteria criteria) {
        return transaction.source(SourcingCondition.conditionFor(criteria))
                          .reduce(new HashMap<String, Long>(), (accumulated, entry) -> {
                              ledgerEventOf(entry.message()).ifPresent(
                                      ledgerEvent -> accumulated.merge(ledgerEvent.account(), ledgerEvent.delta(),
                                                                       Long::sum));
                              return accumulated;
                          })
                          .thenApply(accumulated -> accumulated == null ? new HashMap<String, Long>() : accumulated);
    }

    /**
     * Applies one event to the balances and records everything an oracle needs to attribute it.
     * <p>
     * The record carries four things the framework itself decided and the harness only reads back: the node that
     * handled the event, the segment it was handled under, the position of the event in the stream, and whether the
     * framework considered this delivery part of a replay. Without them a delivery is an anonymous fact -- it cannot be
     * attributed to a segment owner, it cannot be compared against the progress durably stored for that segment, and a
     * legitimate replay cannot be told apart from a duplicate. All four come off the processing context the processor
     * built, so none of them is the harness's opinion.
     */
    private MessageStream.Empty<org.axonframework.messaging.core.Message> project(WorkloadContext context,
                                                                                 EventMessage event,
                                                                                 ProcessingContext processingContext) {
        Optional<LedgerEvent> carried = ledgerEventOf(event);
        if (carried.isPresent()) {
            LedgerEvent ledgerEvent = carried.get();
            boolean first = !idempotentProjection || applied.add(event.identifier());
            if (first) {
                balances.merge(ledgerEvent.account(), ledgerEvent.delta(), Long::sum);
                delivered.incrementAndGet();
            }
            String node = processingContext.getResource(org.axonframework.hunt.harness.HuntNode.NODE_KEY);
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("eventId", event.identifier());
            value.put("delta", ledgerEvent.delta());
            Segment.fromContext(processingContext)
                   .ifPresent(segment -> value.put(HistoryOps.SEGMENT, segment.getSegmentId()));
            TrackingToken.fromContext(processingContext).ifPresent(token -> {
                value.put(HistoryOps.POSITION, token.position().orElse(-1L));
                value.put(HistoryOps.REPLAY, ReplayToken.isReplay(token));
            });
            recorderFor(context, node)
                    .invoke(HistoryOps.DELIVER, ledgerEvent.account(), Map.copyOf(value))
                    .ok(Map.of());
        }
        return MessageStream.empty();
    }

    /**
     * Returns the recorder stamped with the handling node's identity, creating it once per node.
     * <p>
     * One projection is shared by every node, so a single recorder would leave every delivery unattributed. A
     * single-node run has no node identity to stamp and falls back to the unstamped recorder, which is what a
     * single-node history has always carried.
     */
    private HistoryRecorder.ProcessRecorder recorderFor(WorkloadContext context, @Nullable String node) {
        return projectionRecorders.computeIfAbsent(node == null ? "" : node,
                                                   key -> context.recorder()
                                                                 .forProcess("projection",
                                                                             key.isEmpty() ? null : key));
    }

    private static CommandResultMessage accepted() {
        return new GenericCommandResultMessage(new MessageType("ledger.accepted"), "accepted");
    }

    private static EventMessage event(String identifier, LedgerEvent payload) {
        return new GenericEventMessage(identifier, new MessageType(payload.getClass()), payload, Map.of(),
                                       java.time.Instant.EPOCH);
    }

    private static final Map<String, Class<? extends LedgerEvent>> LEDGER_EVENT_TYPES = Map.of(
            new MessageType(MoneyWithdrawn.class).name(), MoneyWithdrawn.class,
            new MessageType(MoneyDeposited.class).name(), MoneyDeposited.class);

    /**
     * Returns the ledger event a message carries, whatever representation the store kept it in.
     * <p>
     * <b>Reading {@code payload()} is not backend-agnostic, and this is where that bites.</b> The in-heap engine stores
     * the {@code EventMessage} itself, so its payload comes back as the object that was appended. A store that persists
     * events keeps a converted representation -- bytes, for the aggregate-based engine -- and hands back a message whose
     * payload is that representation with a converter attached. A handler subscribed programmatically receives the
     * message as it is; only the annotated handler path converts for you. So a workload that pattern-matches on
     * {@code payload()} works on one backend and silently projects nothing on the other, which is a backend difference
     * in the harness rather than in the framework and would otherwise be attributed to the framework.
     * <p>
     * Asking for the type the message declares, and converting to it when the payload is not already it, is correct on
     * both: the conversion short-circuits when the payload is already of the requested type, so the in-heap path needs
     * no converter and takes none.
     *
     * @param message the message to read
     * @return the ledger event, or empty when the message carries something else entirely
     */
    private static Optional<LedgerEvent> ledgerEventOf(org.axonframework.messaging.core.Message message) {
        if (message.payload() instanceof LedgerEvent ledgerEvent) {
            return Optional.of(ledgerEvent);
        }
        Class<? extends LedgerEvent> type = LEDGER_EVENT_TYPES.get(message.type().name());
        return type == null ? Optional.empty() : Optional.of(message.payloadAs(type));
    }

    private static String accountName(int index) {
        return "acct-" + index;
    }

    private static String writerName(int index) {
        return "writer-" + index;
    }

    /**
     * The behaviour every ledger event shares: it names an account and moves an amount.
     *
     * @author Stefan Dragisic
     * @since 5.3.0
     */
    public sealed interface LedgerEvent permits MoneyWithdrawn, MoneyDeposited {

        /**
         * Returns the account the event moves money on.
         *
         * @return the account identifier, which is also the event's tag value
         */
        String account();

        /**
         * Returns the signed effect on that account's balance.
         *
         * @return the amount, negative for a withdrawal and positive for a deposit
         */
        long delta();
    }

    /**
     * Money leaving an account.
     *
     * @param account the account it leaves
     * @param amount  how much, always positive
     * @author Stefan Dragisic
     * @since 5.3.0
     */
    public record MoneyWithdrawn(String account, long amount) implements LedgerEvent {

        @Override
        public long delta() {
            return -amount;
        }
    }

    /**
     * Money arriving in an account.
     *
     * @param account the account it arrives in
     * @param amount  how much, always positive
     * @author Stefan Dragisic
     * @since 5.3.0
     */
    public record MoneyDeposited(String account, long amount) implements LedgerEvent {

        @Override
        public long delta() {
            return amount;
        }
    }

    /**
     * Move an amount from one account to another, refusing if the source cannot cover it.
     *
     * @param from            the account the money leaves
     * @param to              the account the money arrives in
     * @param amount          how much to move
     * @param withdrawEventId the identifier the withdrawal event will carry
     * @param depositEventId  the identifier the deposit event will carry
     * @author Stefan Dragisic
     * @since 5.3.0
     */
    public record Transfer(String from, String to, long amount, String withdrawEventId, String depositEventId) {

    }

    /**
     * Claim an account's whole history, moving an amount out of it and straight back in.
     * <p>
     * The append is anchored at the origin, so it succeeds only against an account nothing has touched yet.
     *
     * @param account         the account to claim
     * @param amount          the amount moved out and back in, leaving the balance unchanged
     * @param withdrawEventId the identifier the withdrawal event will carry
     * @param depositEventId  the identifier the deposit event will carry
     * @author Stefan Dragisic
     * @since 5.3.0
     */
    public record Seize(String account, long amount, String withdrawEventId, String depositEventId) {

    }

    /**
     * Move an amount out of an account and straight back in without reading anything first.
     * <p>
     * This is the control arm. Appending without sourcing is how the framework produces an unconditional append, and
     * an unconditional append is never allowed to be rejected.
     *
     * @param account         the account touched
     * @param amount          the amount moved out and back in, leaving the balance unchanged
     * @param withdrawEventId the identifier the withdrawal event will carry
     * @param depositEventId  the identifier the deposit event will carry
     * @author Stefan Dragisic
     * @since 5.3.0
     */
    public record Rebalance(String account, long amount, String withdrawEventId, String depositEventId) {

    }

    /**
     * Refusal of a transfer the source account cannot cover.
     * <p>
     * This is a business outcome rather than an infrastructure failure, and it is deliberately distinguishable from
     * both: a checker must not mistake a declined transfer for a consistency conflict.
     *
     * @author Stefan Dragisic
     * @since 5.3.0
     */
    public static class InsufficientFundsException extends RuntimeException {

        /**
         * Creates the refusal.
         *
         * @param message which account was short, and by how much
         */
        public InsufficientFundsException(String message) {
            super(message);
        }
    }
}
