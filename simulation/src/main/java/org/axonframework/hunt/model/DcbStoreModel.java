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

package org.axonframework.hunt.model;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * An executable reference model of a Dynamic Consistency Boundary event store.
 * <p>
 * It answers two questions about any point in a history: was this append legal, and what does the store contain now.
 * It is the suite's primary oracle. Property checkers only catch the ways of being wrong that somebody thought of; a
 * reference model catches semantic drift nobody enumerated.
 * <p>
 * The model is pure, deterministic and depends on the JDK alone. It is deliberately <em>sequential</em>: operations
 * take effect one at a time, in the order they are applied. That is what makes it comparable against a TLA+
 * specification of the same rules, and it is also its boundary. The model says nothing about what a concurrent reader
 * may observe while a batch is being committed; that is a separate property, checked against the real engine rather
 * than against this model.
 * <p>
 * Every decision the model makes is attributed to a named rule, so that a divergence points at the rule that
 * disagreed rather than at the model as a whole. The rules:
 * <ul>
 *     <li>{@code MarkerInfinityBypassesConflictCheck} - an append anchored at {@link #INFINITY} never conflicts.</li>
 *     <li>{@code ConflictScanCoversPositionsAtOrAfterMarker} - the scan covers stored events at positions greater
 *     than or equal to the marker; {@link #ORIGIN} is position -1 and therefore covers the whole store.</li>
 *     <li>{@code CriterionTagsMatchByContainsAll} - an event matches a criterion only if it carries all of its
 *     tags.</li>
 *     <li>{@code CriterionTypesMatchByMembershipOrAnyWhenEmpty} - a criterion naming types accepts only those types;
 *     naming none accepts any.</li>
 *     <li>{@code CriteriaMatchIsDisjunctionOverCriteria} - a boundary matches when any of its criteria match; an
 *     empty boundary matches everything.</li>
 *     <li>{@code AppendIsLegalIffNoMatchInScanRange} - the append is accepted exactly when the scan finds no
 *     match.</li>
 *     <li>{@code AcceptedBatchTakesConsecutivePositionsInOfferOrder} - an accepted batch occupies consecutive
 *     positions starting at the current head, assigned in offer order.</li>
 *     <li>{@code CommitMarkerIsLastPositionPlusOne} - the marker an accepted append reports is one past its last
 *     position; an empty batch reports {@link #ORIGIN}.</li>
 *     <li>{@code RejectedAppendLeavesStoreUnchanged} - a rejected append stores nothing, not even part of its
 *     batch.</li>
 *     <li>{@code SourceReturnsMatchingEventsFromStartAscending} - sourcing returns every matching event at or after
 *     the start position, in ascending position order.</li>
 *     <li>{@code SourceMarkerIsStoreHeadAtSourceTime} - the marker a sourcing reports is the store's head at the
 *     moment it read, independent of the boundary and of what matched.</li>
 * </ul>
 * <p>
 * Example usage:
 * <pre>{@code
 * DcbStoreModel model = new DcbStoreModel();
 * SourceResult sourced = model.source(ModelSourcingCondition.conditionFor(boundary));
 * AppendVerdict verdict = model.append(new ModelAppendCondition(sourced.marker(), boundary), events);
 * }</pre>
 *
 * @author Stefan Dragisic
 * @since 5.3.0
 */
public final class DcbStoreModel {

    /**
     * The marker representing the start of the stream. Every stored event matching the boundary is a conflict with
     * it. Encoded as position -1, exactly as the framework's global-index marker resolves it.
     */
    public static final long ORIGIN = -1L;

    /**
     * The marker representing the end of the stream. No stored event can conflict with it.
     */
    public static final long INFINITY = Long.MAX_VALUE;

    private final List<ModelEvent> events = new ArrayList<>();

    /**
     * Attempts an append under the given condition.
     * <p>
     * Applies the conflict rule and, when the append is legal, stores the batch. When it is not, the store is left
     * exactly as it was.
     *
     * @param condition the condition to validate the append against
     * @param batch     the events to append, in offer order
     * @return the verdict, naming the rule that decided it
     */
    public AppendVerdict append(ModelAppendCondition condition, List<ModelEvent> batch) {
        Objects.requireNonNull(condition, "The condition cannot be null.");
        Objects.requireNonNull(batch, "The batch cannot be null.");

        if (condition.marker() == INFINITY) {
            return commit(batch, Rule.MARKER_INFINITY_BYPASSES_CONFLICT_CHECK);
        }
        Long conflict = firstConflict(condition);
        if (conflict != null) {
            return new AppendVerdict(false, Rule.APPEND_IS_LEGAL_IFF_NO_MATCH_IN_SCAN_RANGE, ORIGIN, List.of(),
                                     conflict);
        }
        return commit(batch, Rule.APPEND_IS_LEGAL_IFF_NO_MATCH_IN_SCAN_RANGE);
    }

    /**
     * Reports whether an append under the given condition would be legal, without changing the store.
     *
     * @param condition the condition to validate
     * @return {@code true} when no stored event in the scan range matches the boundary
     */
    public boolean wouldAccept(ModelAppendCondition condition) {
        Objects.requireNonNull(condition, "The condition cannot be null.");
        return condition.marker() == INFINITY || firstConflict(condition) == null;
    }

    /**
     * Reads the boundary the given condition describes.
     *
     * @param condition the condition to read under
     * @return the matching events in ascending position order, and the store's head at the time of reading
     */
    public SourceResult source(ModelSourcingCondition condition) {
        Objects.requireNonNull(condition, "The condition cannot be null.");
        long start = Math.max(0L, condition.start());
        List<ModelEvent> matched = new ArrayList<>();
        for (int position = (int) Math.min(start, events.size()); position < events.size(); position++) {
            ModelEvent event = events.get(position);
            if (ModelCriterion.anyMatches(condition.criteria(), event)) {
                matched.add(event);
            }
        }
        return new SourceResult(List.copyOf(matched), head());
    }

    /**
     * Returns the position one past the last stored event.
     * <p>
     * This is both the position the next accepted append will occupy and the marker a sourcing read reports, which is
     * why an empty store's head is 0 rather than {@link #ORIGIN}.
     *
     * @return the store's head position
     */
    public long head() {
        return events.size();
    }

    /**
     * Returns every stored event, in position order.
     *
     * @return the store's contents; position equals list index
     */
    public List<ModelEvent> events() {
        return List.copyOf(events);
    }

    /**
     * Returns the event stored at the given position.
     *
     * @param position the position to read
     * @return the event at that position, or {@code null} when nothing is stored there
     */
    public @Nullable ModelEvent at(long position) {
        return position < 0 || position >= events.size() ? null : events.get((int) position);
    }

    private @Nullable Long firstConflict(ModelAppendCondition condition) {
        long scanFrom = Math.max(0L, condition.marker());
        for (long position = scanFrom; position < events.size(); position++) {
            if (ModelCriterion.anyMatches(condition.criteria(), events.get((int) position))) {
                return position;
            }
        }
        return null;
    }

    private AppendVerdict commit(List<ModelEvent> batch, Rule rule) {
        if (batch.isEmpty()) {
            return new AppendVerdict(true, rule, ORIGIN, List.of(), null);
        }
        List<Long> positions = new ArrayList<>(batch.size());
        for (ModelEvent event : batch) {
            positions.add((long) events.size());
            events.add(event);
        }
        return new AppendVerdict(true, rule, positions.getLast() + 1, List.copyOf(positions), null);
    }

    /**
     * The named rules the model decides by.
     * <p>
     * A verdict names the rule that produced it, so that a disagreement with a real engine or with a formal
     * specification points at one rule rather than at the model as a whole.
     *
     * @author Stefan Dragisic
     * @since 5.3.0
     */
    public enum Rule {

        /**
         * An append anchored at {@link DcbStoreModel#INFINITY} is accepted without scanning: it claims no boundary,
         * so nothing can conflict with it.
         */
        MARKER_INFINITY_BYPASSES_CONFLICT_CHECK,

        /**
         * The append is accepted exactly when no stored event at or after the marker matches the boundary. The scan
         * range and the matching rules are described on {@link DcbStoreModel}.
         */
        APPEND_IS_LEGAL_IFF_NO_MATCH_IN_SCAN_RANGE
    }

    /**
     * The outcome of an attempted append.
     *
     * @param accepted            whether the append was legal
     * @param rule                the rule that decided it
     * @param marker              the marker the append reports when accepted; {@link DcbStoreModel#ORIGIN} for an
     *                            empty batch and for a rejected append
     * @param positions           the positions the batch occupies when accepted, in offer order; empty otherwise
     * @param conflictingPosition the position of the event that caused the rejection, or {@code null} when accepted
     * @author Stefan Dragisic
     * @since 5.3.0
     */
    public record AppendVerdict(boolean accepted,
                                Rule rule,
                                long marker,
                                List<Long> positions,
                                @Nullable Long conflictingPosition) {

        /**
         * Compact constructor defensively copying the positions.
         */
        public AppendVerdict {
            Objects.requireNonNull(rule, "The rule cannot be null.");
            positions = List.copyOf(Objects.requireNonNull(positions, "The positions cannot be null."));
        }
    }

    /**
     * The outcome of a sourcing read.
     *
     * @param events the matching events, in ascending position order
     * @param marker the store's head at the moment of reading, which is the marker an append derived from this read
     *               is anchored at
     * @author Stefan Dragisic
     * @since 5.3.0
     */
    public record SourceResult(List<ModelEvent> events, long marker) {

        /**
         * Compact constructor defensively copying the events.
         */
        public SourceResult {
            events = List.copyOf(Objects.requireNonNull(events, "The events cannot be null."));
        }

        /**
         * Returns the identifiers of the matching events, in ascending position order.
         *
         * @return the matching events' identifiers
         */
        public List<String> eventIds() {
            return events.stream().map(ModelEvent::id).toList();
        }
    }
}
