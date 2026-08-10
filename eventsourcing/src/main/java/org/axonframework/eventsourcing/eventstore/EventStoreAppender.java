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

import org.axonframework.messaging.core.MessageTypeResolver;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.axonframework.messaging.eventstreaming.EventCriteria;

import java.util.function.UnaryOperator;

import static java.util.Objects.requireNonNull;

/**
 * An {@link EventAppender} that is guaranteed to target an {@link EventStore}, and exposes control over that store's
 * transaction-wide {@link AppendCondition} through {@link #conditional(EventCriteria)},
 * {@link #conditional(AppendCondition)}, and {@link #conditional(UnaryOperator)}.
 * <p>
 * This does <b>not</b> imply that an ordinary {@link EventAppender#append(Object...) append} through a plain
 * {@link EventAppender} is unconditional: any {@link org.axonframework.eventsourcing.eventstore.EventStoreTransaction#source(SourcingCondition)
 * sourcing} that already occurred in the same {@link ProcessingContext} (for example through an event-sourced entity)
 * still guards every event appended in that context, {@code EventStoreAppender} or not. What
 * {@code EventStoreAppender} adds is the ability to <b>replace or extend</b> that condition explicitly, including
 * asserting a condition without having sourced anything at all.
 * <p>
 * Registering a condition through one of the {@code conditional(...)} methods is immediate and transaction-wide: it
 * governs every event appended in the {@link ProcessingContext} this appender was created for, not only the events
 * appended through this specific instance. Multiple calls compose in registration order, mirroring
 * {@link EventStoreTransaction#overrideAppendCondition(UnaryOperator)}. Registering a condition without ever
 * appending an event performs no assertion; the condition is only evaluated when the context commits an append.
 * <p>
 * Use {@link #forContext(ProcessingContext)} to obtain an instance. As with {@link EventAppender}, every invocation
 * returns a fresh instance bound to the given {@link ProcessingContext} - the appender is context-bound, not
 * thread-bound.
 * <p>
 * Example:
 * <pre>{@code
 * @CommandHandler
 * void handle(DefineCourse command, EventStoreAppender events) {
 *     events.conditional(courseDoesNotExist(command.courseId()))
 *           .append(new CourseDefined(command.courseId()));
 * }
 * }</pre>
 *
 * @author Mateusz Nowak
 * @since 5.3.0
 */
public interface EventStoreAppender extends EventAppender {

    /**
     * Creates an appender for the given {@link ProcessingContext}, resolving the active {@link EventStore} and
     * {@link MessageTypeResolver} from the {@code context}'s {@link org.axonframework.common.configuration.Configuration}.
     * <p>
     * Every invocation returns a fresh instance bound to the given {@code context}; see
     * {@link EventAppender#forContext(ProcessingContext)} for why reusing an instance across
     * {@link ProcessingContext#withResource branches} would be unsafe.
     *
     * @param context The {@link ProcessingContext} to create the appender for.
     * @return A fresh appender specific for the given {@code context}.
     */
    static EventStoreAppender forContext(ProcessingContext context) {
        requireNonNull(context, "The context cannot be null");
        return forContext(context, context.component(EventStore.class), context.component(MessageTypeResolver.class));
    }

    /**
     * Creates an appender for the given {@link ProcessingContext}, {@link EventStore}, and {@link MessageTypeResolver}.
     * <p>
     * Every invocation returns a fresh instance bound to the given {@code context}; see
     * {@link EventAppender#forContext(ProcessingContext)} for why reusing an instance across
     * {@link ProcessingContext#withResource branches} would be unsafe.
     *
     * @param context             The {@link ProcessingContext} to create the appender for.
     * @param eventStore          The {@link EventStore} to append events to and register conditions with.
     * @param messageTypeResolver The {@link MessageTypeResolver} to use for the appender.
     * @return A fresh appender specific for the given {@code context}.
     */
    static EventStoreAppender forContext(
            ProcessingContext context,
            EventStore eventStore,
            MessageTypeResolver messageTypeResolver
    ) {
        requireNonNull(context, "The context cannot be null");
        requireNonNull(eventStore, "The eventStore cannot be null");
        requireNonNull(messageTypeResolver, "The messageTypeResolver cannot be null");
        return new DefaultEventStoreAppender(context, eventStore, messageTypeResolver);
    }

    /**
     * Registers the given {@code criteria} as the {@link AppendCondition#criteria()} to check on commit.
     * <p>
     * When no {@link SourcingCondition#source(SourcingCondition) sourcing} occurred yet in this transaction, the
     * resulting condition is {@link AppendCondition#withCriteria(EventCriteria)} - checked from
     * {@link ConsistencyMarker#ORIGIN}. When a sourcing-derived condition already exists, its criteria is replaced
     * while its {@link ConsistencyMarker} is retained, exactly like
     * {@link AppendCondition#replaceCriteria(EventCriteria)}.
     * <p>
     * An {@link AggregateBasedConsistencyMarker} cannot represent an append condition whose criteria differ from
     * what was sourced, since it tracks per-aggregate sequence numbers rather than an arbitrary, criteria-matched
     * position. Replacing the criteria of such a sourced condition therefore fails explicitly.
     *
     * @param criteria The {@link EventCriteria} to check on commit.
     * @return This {@code EventStoreAppender}, for fluent use with {@link EventAppender#append(Object...)}.
     * @throws IllegalArgumentException when a sourced condition exists whose {@link ConsistencyMarker} cannot
     *                                  represent the given, different {@code criteria}.
     */
    EventStoreAppender conditional(EventCriteria criteria);

    /**
     * Registers the given {@code condition} as the complete {@link AppendCondition} to check on commit, replacing
     * whatever condition (sourcing-derived or otherwise) was previously in effect.
     * <p>
     * Pass {@link AppendCondition#none()} to explicitly select an unconditional append.
     *
     * @param condition The {@link AppendCondition} to check on commit.
     * @return This {@code EventStoreAppender}, for fluent use with {@link EventAppender#append(Object...)}.
     */
    EventStoreAppender conditional(AppendCondition condition);

    /**
     * Registers the given {@code transformation} to derive the {@link AppendCondition} to check on commit from
     * whatever condition (sourcing-derived or otherwise) was previously in effect, exactly like
     * {@link EventStoreTransaction#overrideAppendCondition(UnaryOperator)}.
     * <p>
     * Use this for transformations {@link #conditional(EventCriteria)} and {@link #conditional(AppendCondition)}
     * cannot express, such as combining the current condition's criteria with additional criteria while keeping its
     * marker.
     *
     * @param transformation The transformation to derive the {@link AppendCondition} to check on commit.
     * @return This {@code EventStoreAppender}, for fluent use with {@link EventAppender#append(Object...)}.
     */
    EventStoreAppender conditional(UnaryOperator<AppendCondition> transformation);
}
