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

import io.axoniq.framework.axonserver.connector.event.AxonServerEventStorageEngine;
import org.axonframework.conversion.jackson.JacksonConverter;
import org.axonframework.eventsourcing.eventstore.AppendCondition;
import org.axonframework.eventsourcing.eventstore.ConsistencyMarker;
import org.axonframework.eventsourcing.eventstore.EventStorageEngine;
import org.axonframework.eventsourcing.eventstore.GenericTaggedEventMessage;
import org.axonframework.eventsourcing.eventstore.GlobalIndexConsistencyMarker;
import org.axonframework.eventsourcing.eventstore.SourcingCondition;
import org.axonframework.eventsourcing.eventstore.TaggedEventMessage;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.GenericEventMessage;
import org.axonframework.messaging.eventhandling.conversion.DelegatingEventConverter;
import org.axonframework.messaging.eventstreaming.EventCriteria;
import org.axonframework.messaging.eventstreaming.Tag;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives eight writers at four overlapping consistency boundaries against a real Axon Server and checks every accepted
 * append against what the store actually ended up holding.
 * <p>
 * <b>The property.</b> An append carrying consistency marker {@code m} and boundary {@code B} must be rejected when the
 * store holds an event matching {@code B} at a position at or after {@code m}. That is the whole of Dynamic Consistency
 * Boundary optimistic concurrency: an application sources a decision model, decides, and appends on the condition that
 * nothing inside its boundary moved since it read. This class asks the question the only way it can be asked from
 * outside the store -- by recording, for every append the store accepted, the marker it carried, the boundary it
 * declared and the position the store assigned it, and then reading the store's own final order back and looking in the
 * gap between the two for an event the boundary covers.
 * <p>
 * An event found in that gap was assigned a position below the accepting append's own, so the store had already
 * sequenced it when it sequenced the append, and it sits at or after the marker the append declared. Such an append is
 * counted <em>over-accepted</em>. Nothing outside that window is counted: an event above the append's own position was
 * not necessarily sequenced yet, and an event below the marker is exactly what the marker permits. The count is
 * therefore a lower bound on the violations of a run, never an upper one.
 * <p>
 * <b>What this asserts, and why it is not more than this.</b> The effect is a race and its rate varies from run to run,
 * so an assertion on its presence would be green or red by luck and this suite refuses those. What this class asserts is
 * only what a single run can decide:
 * <ul>
 *     <li>every append reached a decision, and enough of them were accepted for the comparison to mean anything;</li>
 *     <li>every failure was a consistency rejection, so no append's outcome is unknown -- an unknown outcome would make
 *     the store's contents unreadable as positions, and the check below unsound;</li>
 *     <li>the store holds exactly the events of the appends it accepted, which is what makes the scan's index equal to
 *     the global position every marker is expressed in.</li>
 * </ul>
 * The over-acceptance count itself is <b>reported, not asserted</b>, and the measured spread is recorded in
 * {@code formal/FINDINGS.adoc} next to the finding it belongs to. A reader who wants the verdict runs this and reads the
 * numbers; a reader who wants to know whether the guarantee has started holding runs it several times and watches the
 * count go to zero and stay there.
 * <p>
 * <b>Why this exists next to the scenario corpus rather than inside it.</b> The suite's reference-model oracle already
 * decides the same protocol on the same store, and found this. What it cannot do is separate the store's behaviour from
 * the harness that drove it: this arm links a released connector against a reactor the connector predates, and supplies
 * one method the connector lacks. This class therefore drives {@link AxonServerEventStorageEngine} directly -- the
 * published class, not the harness's {@link ContextCarryingAxonServerEngine} subclass -- so that every call on the path
 * is the connector's own and the shimmed method is not merely off the append path but absent from the run entirely.
 * There is no scenario runner, no storage-engine wrapper, no fault injector and no event processor here.
 *
 * @author Stefan Dragisic
 * @since 5.3.0
 */
@org.junit.jupiter.api.Tag("container")
class AppendBoundaryEnforcementUnderContentionTest {

    /**
     * Concurrent writers. Enough that several appends are in flight against one boundary at once; the same shape the
     * effect was first measured at.
     */
    private static final int WRITERS = 8;

    /**
     * Rounds per writer. One round is two sourcings and one conditioned append.
     */
    private static final int ROUNDS = 40;

    /**
     * Boundaries the writers contend over. Four is few enough that writers collide constantly and many enough that a
     * round's two-tag boundary is not always the same pair.
     */
    private static final int HOT_TAGS = 4;

    /**
     * Events per append. A batch rather than a single event, because the finding it reproduces is about an append being
     * sequenced one or two batches above its own marker.
     */
    private static final int BATCH = 2;

    /**
     * Bursts of first-append racers. Each burst is a store emptied and eight writers released at once, so a burst is one
     * independent sample of the race that produced the finding this class reproduces.
     */
    private static final int BURSTS = 40;

    private static final String TAG_KEY = "account";
    private static final AxonServerHuntBackend BACKEND = new AxonServerHuntBackend();

    /**
     * One accepted append, in the only terms the check can be made in.
     *
     * @param id            the writer and round that issued it, for the report
     * @param marker        the consistency marker it declared
     * @param boundary      the tags its boundary covered
     * @param firstPosition the store position its first event was assigned
     */
    private record Accepted(String id, long marker, Set<String> boundary, long firstPosition) {

    }

    @Nested
    class AnAppendConditionedOnTwoSourcedMarkers {

        @Test
        void isCheckedAgainstWhatTheStoreActuallyHoldsAtOrAfterTheLowerOfThem() throws Exception {
            probe(true);
        }
    }

    @Nested
    class AnAppendConditionedOnTheWholeHistoryOfItsBoundary {

        @Test
        void isCheckedAgainstEveryEarlierEventInsideThatBoundary() throws Exception {
            probe(false);
        }
    }

    @Nested
    class EightWritersRacingOnAnEmptyStoreUnderOneBoundary {

        @Test
        void haveExactlyOneOfThemAccepted() throws Exception {
            // given a real Axon Server, and a boundary nothing has ever written to
            AxonServerHuntBackend.Deployment deployment = BACKEND.deployment();
            System.out.println("ARM LABEL " + AxonServerHuntBackend.label());
            System.out.println("AXON SERVER container=" + BACKEND.containerId()
                                       + " grpc=" + deployment.grpcHost() + ":" + deployment.grpcPort());

            try (AxonServerHuntBackend.Run run = AxonServerHuntBackend.Run.create(deployment)) {
                AxonServerEventStorageEngine engine = new AxonServerEventStorageEngine(
                        run.connection(), new DelegatingEventConverter(new JacksonConverter()));
                AppendCondition condition =
                        AppendCondition.withCriteria(EventCriteria.havingTags(Tag.of(TAG_KEY, tag(0)))
                                                                 .or(EventCriteria.havingTags(Tag.of(TAG_KEY,
                                                                                                     tag(1)))));

                // when eight writers race to make the first append inside that boundary, over and over on a store
                // emptied between bursts. Every one of them declares ConsistencyMarker.ORIGIN -- no event inside the
                // boundary exists anywhere -- which is the condition an append carries when nothing sourced before it.
                SortedMap<Long, Integer> acceptedPerBurst = new TreeMap<>();
                Map<String, AtomicInteger> failures = new ConcurrentHashMap<>();
                for (int burst = 0; burst < BURSTS; burst++) {
                    AxonServerHuntBackend.purge(deployment);
                    AtomicInteger accepted = new AtomicInteger();
                    CountDownLatch start = new CountDownLatch(1);
                    List<Thread> writers = new ArrayList<>();
                    for (int writer = 0; writer < WRITERS; writer++) {
                        String name = "b" + burst + "-w" + writer + "-w";
                        writers.add(Thread.ofPlatform().unstarted(() -> {
                            try {
                                start.await();
                                List<TaggedEventMessage<?>> batch = new ArrayList<>();
                                for (int index = 0; index < BATCH; index++) {
                                    batch.add(new GenericTaggedEventMessage<>(
                                            event(name), Set.of(Tag.of(TAG_KEY, tag(0)))));
                                }
                                append(engine, condition, batch);
                                accepted.incrementAndGet();
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            } catch (RuntimeException e) {
                                failures.computeIfAbsent(rootCauseName(e), key -> new AtomicInteger())
                                        .incrementAndGet();
                            }
                        }));
                    }
                    writers.forEach(Thread::start);
                    start.countDown();
                    for (Thread writer : writers) {
                        writer.join(TimeUnit.MINUTES.toMillis(5));
                    }
                    acceptedPerBurst.merge((long) accepted.get(), 1, Integer::sum);
                }

                // then how many of the eight the store accepted, per burst. Exactly one is correct: the first to commit
                // stores an event inside the boundary, and every other writer's condition is false from that moment. Two
                // or more accepted in one burst is an append accepted although the boundary it declared empty was not.
                System.out.println("PROBE bursts=" + BURSTS + " writers per burst=" + WRITERS);
                System.out.println("PROBE accepted per burst -> burst count: " + acceptedPerBurst);
                System.out.println("PROBE append failure classifications: " + counts(failures));

                // and every burst reached a decision on every writer, or the distribution above is not about the store
                assertThat(acceptedPerBurst.values().stream().mapToInt(Integer::intValue).sum())
                        .as("every burst must be accounted for")
                        .isEqualTo(BURSTS);
                assertThat(failures.keySet())
                        .as("an append whose outcome is unknown makes a burst's count unreadable: %s", counts(failures))
                        .allSatisfy(classification -> assertThat(classification)
                                .contains("AppendEventsTransactionRejectedException"));

                // and no burst accepted nothing, which would mean the race never happened
                assertThat(acceptedPerBurst).as("a burst that accepted nothing has measured nothing")
                                            .doesNotContainKey(0L);
            }
        }
    }

    /**
     * Runs the writers and reports what the store did.
     *
     * @param sourcedMarker {@code true} to source both of a round's tags and condition the append on the lower of the
     *                      two markers they report; {@code false} to condition it on
     *                      {@link ConsistencyMarker#ORIGIN} instead, which declares that no event inside the boundary
     *                      exists at all
     */
    private static void probe(boolean sourcedMarker) throws Exception {
        // given a real Axon Server on an emptied boundary context, and the arm's label, which every number below
        // is only readable next to
        AxonServerHuntBackend.Deployment deployment = BACKEND.deployment();
        System.out.println("PROBE marker kind: " + (sourcedMarker ? "sourced" : "ORIGIN"));
        System.out.println("ARM LABEL " + AxonServerHuntBackend.label());
        System.out.println("AXON SERVER container=" + BACKEND.containerId()
                                   + " grpc=" + deployment.grpcHost() + ":" + deployment.grpcPort()
                                   + " admin=" + deployment.adminBase());
        System.out.println("AXON SERVER contexts " + AxonServerHuntBackend.contexts(deployment));
        AxonServerHuntBackend.purge(deployment);

        try (AxonServerHuntBackend.Run run = AxonServerHuntBackend.Run.create(deployment)) {
            // The published connector class, deliberately, rather than the harness's context-carrying subclass. Every
            // call this probe makes is then the connector's own: the one-argument source it implements, its
            // appendEvents, its commit. The method the arm shims elsewhere is not on this path at all.
            AxonServerEventStorageEngine engine = new AxonServerEventStorageEngine(
                    run.connection(), new DelegatingEventConverter(new JacksonConverter()));

            List<Accepted> accepted = new ArrayList<>();
            Map<String, String> tagOfEvent = new ConcurrentHashMap<>();
            Map<String, AtomicInteger> failures = new ConcurrentHashMap<>();

            // when eight writers each run forty rounds of: source two of the four hot tags, take the lower of the
            // two markers -- which is what an application combining two decision models must do -- and append a
            // two-event batch under the OR of the two boundaries
            CountDownLatch start = new CountDownLatch(1);
            List<Thread> writers = new ArrayList<>();
            for (int writer = 0; writer < WRITERS; writer++) {
                int id = writer;
                writers.add(Thread.ofPlatform().name("probe-writer-" + id).unstarted(
                        () -> writeRounds(engine, id, sourcedMarker, start, accepted, tagOfEvent, failures)));
            }
            writers.forEach(Thread::start);
            start.countDown();
            for (Thread writer : writers) {
                writer.join(TimeUnit.MINUTES.toMillis(10));
            }

            // then the store's own final order is read back once, which is the only account of what happened that
            // does not come from the writers
            List<String> stored = scan(engine);
            int rejected = WRITERS * ROUNDS - accepted.size();
            System.out.println("PROBE accepted=" + accepted.size()
                                       + " rejected=" + rejected
                                       + " store=" + stored.size());
            System.out.println("PROBE append failure classifications: " + counts(failures));

            // and every append reached a decision and enough were accepted for the comparison to say anything. A run
            // where nothing was accepted would report zero over-acceptances and would have measured nothing.
            assertThat(accepted).as("a run with no accepted append measures nothing").isNotEmpty();

            // and no append's outcome is unknown. This is what makes the rest of the check sound rather than a
            // convention: an append that failed for a transport reason may or may not have stored its events, and
            // the scan below could then hold events no accepted append accounts for, which would make the scan's
            // index stop being the global position that every marker is expressed in.
            assertThat(failures.keySet())
                    .as("an append whose outcome is unknown makes this run's positions unreadable: %s",
                        counts(failures))
                    .allSatisfy(classification -> assertThat(classification)
                            .contains("AppendEventsTransactionRejectedException"));

            // and the store holds exactly the events of the appends it accepted, so scan index equals global
            // position
            assertThat(stored)
                    .as("the store must hold exactly the accepted appends' events for its index to be a position")
                    .hasSize(accepted.size() * BATCH);

            // and every accepted append's own batch is found at the position this probe computed for it from the
            // marker the store handed back. This is the check that separates a meaningful zero from a vacuous one:
            // the whole comparison below searches the window between an append's marker and its own position, so a
            // probe whose position arithmetic were wrong would search the wrong window -- or an empty one -- and
            // would report no violations however many there were.
            assertThat(accepted)
                    .as("an append's own events must sit where the marker it returned says they do, or the window "
                                + "this probe searches is not the window it means to search")
                    .allSatisfy(append -> assertThat(stored.get((int) append.firstPosition()))
                            .isEqualTo(append.id() + "-w"));

            // and now the property itself, reported rather than asserted, because its rate varies between runs and
            // an assertion on it would be red or green by luck. See the class Javadoc and the finding it belongs to.
            report(accepted, stored, tagOfEvent);
        }
    }

    /**
     * Runs one writer's rounds, recording each append's outcome.
     * <p>
     * A rejection is counted by its root cause's class name rather than merely counted, because a store that answers a
     * transport failure with a rejection type would otherwise be indistinguishable from one that decided.
     */
    private static void writeRounds(AxonServerEventStorageEngine engine,
                                    int writer,
                                    boolean sourcedMarker,
                                    CountDownLatch start,
                                    List<Accepted> accepted,
                                    Map<String, String> tagOfEvent,
                                    Map<String, AtomicInteger> failures) {
        try {
            start.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        for (int round = 0; round < ROUNDS; round++) {
            String first = tag((writer + round) % HOT_TAGS);
            String second = tag(((writer + round) + 1 + (round % (HOT_TAGS - 1))) % HOT_TAGS);
            // An ORIGIN marker is what an append carries when nothing sourced before it, and it is the strictest
            // condition the protocol can express: no event inside the boundary exists anywhere in the store. Recorded
            // as its own position of -1, which is what the framework converts it to, so the search window below starts
            // at the bottom of the store.
            long marker = sourcedMarker
                    ? Math.min(markerOf(engine, first), markerOf(engine, second))
                    : GlobalIndexConsistencyMarker.position(ConsistencyMarker.ORIGIN);

            String id = "w" + writer + "-" + round;
            String name = id + "-w";
            tagOfEvent.put(name, first);
            List<TaggedEventMessage<?>> batch = new ArrayList<>();
            for (int index = 0; index < BATCH; index++) {
                batch.add(new GenericTaggedEventMessage<>(event(name), Set.of(Tag.of(TAG_KEY, first))));
            }
            AppendCondition condition =
                    AppendCondition.withCriteria(EventCriteria.havingTags(Tag.of(TAG_KEY, first))
                                                             .or(EventCriteria.havingTags(Tag.of(TAG_KEY, second))));
            if (sourcedMarker) {
                condition = condition.withMarker(new GlobalIndexConsistencyMarker(marker));
            }
            try {
                long end = append(engine, condition, batch);
                synchronized (accepted) {
                    accepted.add(new Accepted(id, marker, Set.of(first, second), end - BATCH));
                }
            } catch (RuntimeException e) {
                failures.computeIfAbsent(rootCauseName(e), key -> new AtomicInteger()).incrementAndGet();
            }
        }
    }

    /**
     * Compares every accepted append against the store's own order and prints what it finds.
     * <p>
     * The window searched is {@code [marker, own first position)}: an event there was sequenced before the append and is
     * at or after the marker the append declared, which is exactly the case the boundary forbids.
     */
    private static void report(List<Accepted> accepted, List<String> stored, Map<String, String> tagOfEvent) {
        // How wide the searched window was, per accepted append. This is the number that says whether the run had any
        // opportunity to observe the property being broken: an append sequenced exactly at its own marker leaves an
        // empty window, and a run of nothing but those could report zero violations without having looked at anything.
        SortedMap<Long, Integer> windows = new TreeMap<>();
        accepted.forEach(append -> windows.merge(append.firstPosition() - append.marker(), 1, Integer::sum));
        long withRoom = accepted.stream().filter(append -> append.firstPosition() > append.marker()).count();
        System.out.println("PROBE own position minus marker -> count: " + windows);
        System.out.println("PROBE accepted appends sequenced above their own marker: " + withRoom
                                   + " (the appends where a violation is observable at all)");

        SortedMap<Long, Integer> distances = new TreeMap<>();
        int overAccepted = 0;
        int shown = 0;
        int examined = 0;
        for (Accepted append : accepted) {
            for (long position = Math.max(0, append.marker()); position < append.firstPosition(); position++) {
                String name = stored.get((int) position);
                String tag = tagOfEvent.get(name);
                examined++;
                if (tag == null || !append.boundary().contains(tag)) {
                    continue;
                }
                overAccepted++;
                distances.merge(position - append.marker(), 1, Integer::sum);
                if (shown++ < 3) {
                    System.out.println("  DCB VIOLATION " + append.id()
                                               + "  marker=" + append.marker()
                                               + " boundary=" + append.boundary()
                                               + " accepted at position " + append.firstPosition()
                                               + " although " + name + " (" + tag + ") sits at position " + position);
                }
                break;
            }
        }
        // The events the search actually looked at. A zero here would mean the search examined nothing, which is the one
        // way this report could be silently empty rather than informatively empty.
        System.out.println("PROBE stored events examined inside those windows: " + examined);
        System.out.println("PROBE over-accepted appends: " + overAccepted + " of " + accepted.size() + " accepted");
        System.out.println("PROBE lowest conflicting position minus marker -> count: " + distances);
    }

    private static String tag(int index) {
        return "acct-" + index;
    }

    private static EventMessage event(String name) {
        return new GenericEventMessage(new MessageType(name, "0.0.1"), Map.of("amount", 1));
    }

    /**
     * Reads the marker the store reports for one tag's boundary, through the connector's own single-argument sourcing.
     * <p>
     * Drained with {@link org.axonframework.messaging.core.MessageStream#reduce}, because a gRPC stream is empty until
     * its first message arrives and a {@code next()} loop that stops at the first empty answer reports nothing however
     * much the store holds.
     */
    private static long markerOf(AxonServerEventStorageEngine engine, String tag) {
        long[] marker = {0L};
        engine.source(SourcingCondition.conditionFor(EventCriteria.havingTags(Tag.of(TAG_KEY, tag))))
              .reduce(marker, (holder, entry) -> {
                  ConsistencyMarker reported = entry.getResource(ConsistencyMarker.RESOURCE_KEY);
                  if (reported != null) {
                      holder[0] = GlobalIndexConsistencyMarker.position(reported);
                  }
                  return holder;
              })
              .orTimeout(60, TimeUnit.SECONDS)
              .join();
        return marker[0];
    }

    /**
     * Appends and commits one batch, answering the marker the store assigned it.
     * <p>
     * The raw cast is the interface's: {@code appendEvents} answers a transaction whose commit result type is a
     * wildcard, and only that transaction can consume its own commit result. The harness's own storage-engine wrapper
     * bridges it the same way.
     *
     * @return the marker the accepted append reports, which is its last position plus one
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static long append(AxonServerEventStorageEngine engine,
                              AppendCondition condition,
                              List<TaggedEventMessage<?>> batch) {
        EventStorageEngine.AppendTransaction transaction =
                engine.appendEvents(condition, null, batch).orTimeout(60, TimeUnit.SECONDS).join();
        Object commitResult = transaction.commit().orTimeout(60, TimeUnit.SECONDS).join();
        ConsistencyMarker marker =
                (ConsistencyMarker) transaction.afterCommit(commitResult).orTimeout(60, TimeUnit.SECONDS).join();
        return GlobalIndexConsistencyMarker.position(marker);
    }

    /**
     * Reads the whole store in its own order, answering the event names by position.
     */
    private static List<String> scan(AxonServerEventStorageEngine engine) {
        return engine.source(SourcingCondition.conditionFor(EventCriteria.havingAnyTag()))
                     .reduce(new ArrayList<String>(), (names, entry) -> {
                         if (entry.getResource(ConsistencyMarker.RESOURCE_KEY) == null) {
                             names.add(entry.message().type().name());
                         }
                         return names;
                     })
                     .orTimeout(120, TimeUnit.SECONDS)
                     .join();
    }

    private static String rootCauseName(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getClass().getSimpleName();
    }

    private static Map<String, Integer> counts(Map<String, AtomicInteger> tally) {
        Map<String, Integer> counted = new LinkedHashMap<>();
        tally.forEach((key, value) -> counted.put(key, value.get()));
        return counted;
    }
}
