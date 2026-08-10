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

package org.axonframework.eventsourcing.eventstore;

import org.axonframework.common.annotation.Internal;
import org.axonframework.messaging.core.Context.ResourceKey;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventstreaming.EventCriteria;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static java.util.Objects.requireNonNull;

/**
 * Coordinates asymmetric {@link EventCriteria} between {@link EventStoreTransaction#source(SourcingCondition)
 * sourcing} and appending within a single {@link ProcessingContext}.
 * <p>
 * An entity may source its decision model using one {@link EventCriteria} while guarding its append against a
 * different, associated {@link EventCriteria}. Since a single {@link ProcessingContext} (transaction) can source
 * multiple entities, this class accumulates every effective append criterion into a single union, and installs at
 * most one {@link EventStoreTransaction#overrideAppendCondition(java.util.function.UnaryOperator) override} per
 * transaction that replaces the sourcing-derived criteria with that union at commit time, while preserving the
 * sourced {@link ConsistencyMarker}.
 * <p>
 * Callers that resolve a per-entity append criterion {@link #associateAppendCriteria(ProcessingContext,
 * SourcingCondition, EventCriteria) associate} it with the exact {@link SourcingCondition} instance they are about
 * to {@link EventStoreTransaction#source(SourcingCondition) source} with. {@link DefaultEventStoreTransaction}
 * consumes that association as part of {@code source(...)}. Contributions that are never associated (direct,
 * low-level {@code source(...)} calls, or entities configured symmetrically) contribute their own sourcing criteria
 * symmetrically. Associations are tracked per {@link SourcingCondition} <em>instance</em> (not by value equality),
 * so that concurrently loading entities never contend on each other's declarations, and that a transaction wrapper
 * which builds a new {@link SourcingCondition} instance must explicitly
 * {@link #transferAssociation(ProcessingContext, SourcingCondition, SourcingCondition) transfer} the association,
 * rather than one accidentally being picked up by unrelated, merely equal, criteria.
 *
 * @author Mateusz Nowak
 * @since 5.3.0
 */
@Internal
public final class AppendCriteriaCoordinator {

    private static final ResourceKey<ConcurrentMap<IdentityKey, EventCriteria>> PENDING_APPEND_CRITERIA =
            ResourceKey.withLabel("pendingAppendCriteria");
    private static final ResourceKey<EventCriteria> APPEND_CRITERIA_UNION =
            ResourceKey.withLabel("appendCriteriaUnion");
    private static final ResourceKey<Boolean> OVERRIDE_INSTALLED =
            ResourceKey.withLabel("appendCriteriaOverrideInstalled");

    private AppendCriteriaCoordinator() {
        // Utility class
    }

    /**
     * Associates the given {@code appendCriteria} with the given {@code sourcingCondition} instance, to be consumed
     * by the matching {@link EventStoreTransaction#source(SourcingCondition) source(...)} call that is about to
     * happen with that exact {@code sourcingCondition} instance.
     * <p>
     * This is a no-op when {@code appendCriteria} equals {@code sourcingCondition.criteria()}: symmetric
     * contributions never engage this coordinator, so a fully symmetric transaction never installs an
     * {@link EventStoreTransaction#overrideAppendCondition(java.util.function.UnaryOperator) override}.
     *
     * @param processingContext The {@link ProcessingContext} of the transaction the association is scoped to.
     * @param sourcingCondition The exact {@link SourcingCondition} instance about to be sourced.
     * @param appendCriteria    The {@link EventCriteria} to guard the resulting append with.
     * @throws IllegalStateException if the given {@code sourcingCondition} instance was already associated and not
     *                               yet consumed by a matching {@code source(...)} call.
     */
    public static void associateAppendCriteria(ProcessingContext processingContext,
                                                SourcingCondition sourcingCondition,
                                                EventCriteria appendCriteria) {
        requireNonNull(processingContext, "The processingContext cannot be null");
        requireNonNull(sourcingCondition, "The sourcingCondition cannot be null");
        requireNonNull(appendCriteria, "The appendCriteria cannot be null");
        if (appendCriteria.equals(sourcingCondition.criteria())) {
            return;
        }
        EventCriteria previous = pendingAssociations(processingContext)
                .putIfAbsent(new IdentityKey(sourcingCondition), appendCriteria);
        if (previous != null) {
            throw new IllegalStateException(
                    "Cannot associate append criteria for [" + sourcingCondition + "]: it was already associated "
                            + "with append criteria [" + previous + "] which was never consumed by a matching "
                            + "source(...) call."
            );
        }
    }

    /**
     * Transfers a pending association from {@code from} to {@code to}, for {@link EventStoreTransaction} decorators
     * that build a new {@link SourcingCondition} instance (e.g. by widening its criteria) before delegating a
     * {@code source(...)} call.
     * <p>
     * A decorator that replaces the {@link SourcingCondition} instance without calling this method causes the
     * {@link #associateAppendCriteria(ProcessingContext, SourcingCondition, EventCriteria) associated} append
     * criteria to be left unconsumed, which is reported explicitly at commit time rather than silently falling back
     * to symmetric behavior.
     *
     * @param processingContext The {@link ProcessingContext} of the transaction the association is scoped to.
     * @param from               The original {@link SourcingCondition} instance.
     * @param to                 The replacement {@link SourcingCondition} instance that will actually be sourced.
     * @return The given {@code to} instance, for fluent use at the call site.
     */
    public static SourcingCondition transferAssociation(ProcessingContext processingContext,
                                                         SourcingCondition from,
                                                         SourcingCondition to) {
        requireNonNull(processingContext, "The processingContext cannot be null");
        requireNonNull(from, "The from SourcingCondition cannot be null");
        requireNonNull(to, "The to SourcingCondition cannot be null");
        Map<IdentityKey, EventCriteria> pending = processingContext.getResource(PENDING_APPEND_CRITERIA);
        if (pending != null) {
            EventCriteria appendCriteria = pending.remove(new IdentityKey(from));
            if (appendCriteria != null) {
                pending.put(new IdentityKey(to), appendCriteria);
            }
        }
        return to;
    }

    /**
     * Consumes any association pending for the given {@code condition} instance, folding the effective append
     * criterion (the associated {@link EventCriteria}, or {@code condition.criteria()} itself when no association
     * is pending) into this transaction's append-criteria union, installing the coordinating
     * {@link EventStoreTransaction#overrideAppendCondition(java.util.function.UnaryOperator) override} on first use.
     *
     * @param processingContext The {@link ProcessingContext} of the transaction being sourced.
     * @param transaction        The {@link EventStoreTransaction} being sourced, on which the override is installed.
     * @param condition          The {@link SourcingCondition} passed to {@code source(...)}.
     */
    static void consume(ProcessingContext processingContext,
                        EventStoreTransaction transaction,
                        SourcingCondition condition) {
        Map<IdentityKey, EventCriteria> pending = processingContext.getResource(PENDING_APPEND_CRITERIA);
        EventCriteria associated = pending == null ? null : pending.remove(new IdentityKey(condition));
        EventCriteria contribution;
        if (associated == null) {
            contribution = condition.criteria();
        } else {
            contribution = associated;
            installOverrideOnce(processingContext, transaction);
        }
        EventCriteria finalContribution = contribution;
        processingContext.updateResource(
                APPEND_CRITERIA_UNION,
                existing -> existing == null ? finalContribution : existing.or(finalContribution)
        );
    }

    private static void installOverrideOnce(ProcessingContext processingContext, EventStoreTransaction transaction) {
        // A plain putResourceIfAbsent (rather than computeResourceIfAbsent) so overrideAppendCondition(...) - which
        // itself mutates a resource on this same ProcessingContext - is never invoked from within a resource-store
        // callback; the ConcurrentHashMap backing the resource store rejects such reentrant updates.
        Boolean alreadyInstalled = processingContext.putResourceIfAbsent(OVERRIDE_INSTALLED, Boolean.TRUE);
        if (alreadyInstalled == null) {
            transaction.overrideAppendCondition(current -> {
                assertCriteriaReplacementSupported(current.consistencyMarker());
                return current.replaceCriteria(processingContext.getResource(APPEND_CRITERIA_UNION));
            });
        }
    }

    /**
     * Asserts that the given {@code marker} can represent an append condition whose criteria differ from what was
     * sourced.
     * <p>
     * An {@link AggregateBasedConsistencyMarker} tracks per-aggregate sequence numbers, not an arbitrary,
     * criteria-matched position, so it cannot represent an append condition whose criteria differ from what was
     * sourced.
     *
     * @param marker The {@link ConsistencyMarker} to assert criteria-replacement support for.
     * @throws IllegalArgumentException when {@code marker} is an {@link AggregateBasedConsistencyMarker}.
     */
    static void assertCriteriaReplacementSupported(ConsistencyMarker marker) {
        if (marker instanceof AggregateBasedConsistencyMarker abcm) {
            throw new IllegalArgumentException(
                    "Asymmetric append criteria are not supported when sourcing resolved an "
                            + "AggregateBasedConsistencyMarker: " + abcm + ". Such a marker can only "
                            + "represent an append condition whose criteria are identical to what was "
                            + "sourced, since it tracks per-aggregate sequence numbers rather than an "
                            + "arbitrary, criteria-matched position."
            );
        }
    }

    /**
     * Fails explicitly if an association was declared but never consumed by a matching
     * {@link EventStoreTransaction#source(SourcingCondition) source(...)} call, rather than silently restoring
     * symmetric behavior. This also catches transaction wrappers that replaced a {@link SourcingCondition} instance
     * without {@link #transferAssociation(ProcessingContext, SourcingCondition, SourcingCondition) transferring} its
     * association.
     *
     * @param processingContext The {@link ProcessingContext} of the transaction about to be committed.
     * @throws IllegalStateException if an association was declared but never consumed.
     */
    static void failIfUnconsumed(ProcessingContext processingContext) {
        Map<IdentityKey, EventCriteria> pending = processingContext.getResource(PENDING_APPEND_CRITERIA);
        if (pending != null && !pending.isEmpty()) {
            throw new IllegalStateException(
                    "Append criteria " + pending.values() + " was declared but never consumed by a matching "
                            + "source(...) call. An EventStoreTransaction wrapper may have replaced the "
                            + "SourcingCondition without transferring the association."
            );
        }
    }

    private static ConcurrentMap<IdentityKey, EventCriteria> pendingAssociations(ProcessingContext processingContext) {
        return processingContext.computeResourceIfAbsent(PENDING_APPEND_CRITERIA, ConcurrentHashMap::new);
    }

    /**
     * Wraps a {@link SourcingCondition} to key the pending-association map by instance identity rather than by
     * {@link SourcingCondition#equals(Object) value equality}: two distinct entity loads that happen to resolve
     * structurally equal criteria must not be conflated.
     */
    private static final class IdentityKey {

        private final SourcingCondition condition;

        private IdentityKey(SourcingCondition condition) {
            this.condition = condition;
        }

        @Override
        public boolean equals(Object obj) {
            return obj instanceof IdentityKey other && other.condition == condition;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(condition);
        }
    }
}
