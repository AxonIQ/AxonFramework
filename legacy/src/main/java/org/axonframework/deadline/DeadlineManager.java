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

package org.axonframework.deadline;

import org.jspecify.annotations.Nullable;
import org.axonframework.messaging.core.Scope;
import org.axonframework.messaging.core.ScopeDescriptor;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.Metadata;

import java.time.Duration;
import java.time.Instant;

/**
 * Contract for deadline managers. Contains methods for scheduling a deadline and for cancelling a deadline.
 *
 * @author Milan Savic
 * @author Steven van Beelen
 * @since 3.3
 */
public interface DeadlineManager {

    /**
     * Schedules a deadline at given {@code triggerDateTime} with given {@code deadlineName}. The payload of this
     * deadline will be {@code null}, as none is provided. The returned {@code scheduleId} and provided {@code
     * deadlineName} combination can be used to cancel the scheduled deadline. The scope within which this call is made
     * will be retrieved by the DeadlineManager itself.
     *
     * @param triggerDateTime A {@link java.time.Instant} denoting the moment to trigger the deadline handling
     * @param deadlineName    A {@link String} representing the name of the deadline to schedule
     * @return the {@code scheduleId} as a {@link String} to use when cancelling the schedule
     */
    default String schedule(Instant triggerDateTime, String deadlineName) {
        return schedule(triggerDateTime, deadlineName, null);
    }

    /**
     * Schedules a deadline at given {@code triggerDateTime} with given {@code deadlineName}. The returned
     * {@code scheduleId} and provided {@code deadlineName} combination can be used to cancel the scheduled deadline.
     * The scope within which this call is made will be retrieved by the DeadlineManager itself.
     * <p>
     * The given {@code messageOrPayload} may be any object, as well as a DeadlineMessage. In the latter case, the
     * instance provided is the donor for the payload and {@link Metadata} of the actual
     * deadline being used. In the former case, the given {@code messageOrPayload} will be wrapped as the payload of a
     * {@link DeadlineMessage}.
     * </p>
     *
     * @param triggerDateTime  A {@link java.time.Instant} denoting the moment to trigger the deadline handling
     * @param deadlineName     A {@link String} representing the name of the deadline to schedule
     * @param messageOrPayload A {@link Message} or payload for a message as an
     *                         {@link Object}
     * @return the {@code scheduleId} as a {@link String} to use when cancelling the schedule
     */
    default String schedule(Instant triggerDateTime, String deadlineName,
                            @Nullable Object messageOrPayload) {
        return schedule(triggerDateTime, deadlineName, messageOrPayload, Scope.describeCurrentScope());
    }

    /**
     * Schedules a deadline at given {@code triggerDateTime} with provided context. The returned {@code scheduleId} and
     * provided {@code deadlineName} combination can be used to cancel the scheduled deadline.
     * <p>
     * The given {@code messageOrPayload} may be any object, as well as a DeadlineMessage. In the latter case, the
     * instance provided is the donor for the payload and {@link Metadata} of the actual
     * deadline being used. In the former case, the given {@code messageOrPayload} will be wrapped as the payload of a
     * {@link DeadlineMessage}.
     * </p>
     *
     * @param triggerDateTime  A {@link Instant} denoting the moment to trigger the deadline handling
     * @param deadlineName     A {@link String} representing the name of the deadline to schedule
     * @param messageOrPayload A {@link Message} or payload for a message as an
     *                         {@link Object}
     * @param deadlineScope    A {@link ScopeDescriptor} describing the scope within which the deadline was scheduled
     * @return the {@code scheduleId} as a {@link String} to use when cancelling the schedule
     */
    String schedule(Instant triggerDateTime,
                    String deadlineName,
                    @Nullable Object messageOrPayload,
                    ScopeDescriptor deadlineScope);

    /**
     * Schedules a deadline after the given {@code triggerDuration} with given {@code deadlineName}. The payload of this
     * deadline will be {@code null}, as none is provided. The returned {@code scheduleId} and provided {@code
     * deadlineName} combination can be used to cancel the scheduled deadline. The scope within which this call is made
     * will be retrieved by the DeadlineManager itself.
     *
     * @param triggerDuration A {@link java.time.Duration} describing the waiting period before handling the deadline
     * @param deadlineName    A {@link String} representing the name of the deadline to schedule
     * @return the {@code scheduleId} as a {@link String} to use when cancelling the schedule
     */
    default String schedule(Duration triggerDuration, String deadlineName) {
        return schedule(triggerDuration, deadlineName, null);
    }

    /**
     * Schedules a deadline after the given {@code triggerDuration}. The returned {@code scheduleId} and provided
     * {@code deadlineName} combination can be used to cancel the scheduled deadline.
     * The scope within which this call is made will be retrieved by the DeadlineManager
     * itself.
     * <p>
     * The given {@code messageOrPayload} may be any object, as well as a DeadlineMessage. In the latter case, the
     * instance provided is the donor for the payload and {@link Metadata} of the actual
     * deadline being used. In the former case, the given {@code messageOrPayload} will be wrapped as the payload of a
     * {@link DeadlineMessage}.
     * </p>
     *
     * @param triggerDuration  A {@link java.time.Duration} describing the waiting period before handling the deadline
     * @param deadlineName     A {@link String} representing the name of the deadline to schedule
     * @param messageOrPayload A {@link Message} or payload for a message as an
     *                         {@link Object}
     * @return the {@code scheduleId} as a {@link String} to use when cancelling the schedule
     */
    default String schedule(Duration triggerDuration, String deadlineName,
                            @Nullable Object messageOrPayload) {
        return schedule(triggerDuration, deadlineName, messageOrPayload, Scope.describeCurrentScope());
    }

    /**
     * Schedules a deadline after the given {@code triggerDuration} with provided context. The provided
     * {@code deadlineName} / {@code scheduleId} combination can be used to cancel the scheduled deadline.
     * <p>
     * The given {@code messageOrPayload} may be any object, as well as a DeadlineMessage. In the latter case, the
     * instance provided is the donor for the payload and {@link Metadata} of the actual
     * deadline being used. In the former case, the given {@code messageOrPayload} will be wrapped as the payload of a
     * {@link DeadlineMessage}.
     * </p>
     *
     * @param triggerDuration  A {@link Duration} describing the waiting period before handling the deadline
     * @param deadlineName     A {@link String} representing the name of the deadline to schedule
     * @param messageOrPayload A {@link Message} or payload for a message as an
     *                         {@link Object}
     * @param deadlineScope    A {@link ScopeDescriptor} describing the scope within which the deadline was scheduled
     * @return the {@code scheduleId} as a {@link String} to use when cancelling the schedule
     */
    default String schedule(Duration triggerDuration,
                            String deadlineName,
                            @Nullable Object messageOrPayload,
                            ScopeDescriptor deadlineScope) {
        return schedule(Instant.now().plus(triggerDuration),
                        deadlineName,
                        messageOrPayload,
                        deadlineScope);
    }

    /**
     * Cancels the deadline corresponding to the given {@code deadlineName} / {@code scheduleId} combination. This
     * method has no impact on deadlines which have already been triggered.
     *
     * @param deadlineName a {@link String} representing the name of the deadline to cancel
     * @param scheduleId   the {@link String} denoting the scheduled deadline to cancel
     */
    void cancelSchedule(String deadlineName, String scheduleId);

    /**
     * Cancels all the deadlines corresponding to the given {@code deadlineName}. This method has no impact on deadlines
     * which have already been triggered.
     *
     * @param deadlineName a {@link String} representing the name of the deadlines to cancel
     */
    void cancelAll(String deadlineName);

    /**
     * Cancels all deadlines corresponding to the given {@code deadlineName} that are scheduled within {@link
     * Scope#describeCurrentScope()}. This method has no impact on deadlines which have already been triggered.
     *
     * @param deadlineName a {@link String} representing the name of the deadlines to cancel
     */
    default void cancelAllWithinScope(String deadlineName) {
        cancelAllWithinScope(deadlineName, Scope.describeCurrentScope());
    }

    /**
     * Cancels all deadlines corresponding to the given {@code deadlineName} and {@code scope}.
     * This method has no impact on deadlines which have already been triggered.
     *
     * @param deadlineName a {@link String} representing the name of the deadlines to cancel
     * @param scope        a {@link ScopeDescriptor} describing the scope within which the deadline was scheduled
     */
    void cancelAllWithinScope(String deadlineName, ScopeDescriptor scope);

    /**
     * Shuts down this deadline manager.
     */
    default void shutdown() {
    }
}
