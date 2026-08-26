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

import org.axonframework.messaging.core.ScopeDescriptor;
import org.axonframework.messaging.tracing.Span;

/**
 * Span factory that creates spans for the {@link DeadlineManager}. You can customize the spans of the bus by creating
 * your own implementation.
 *
 * @author Mitchell Herrijgers
 * @since 4.9.0
 */
public interface DeadlineManagerSpanFactory {

    /**
     * Creates a span that represents the scheduling of a deadline.
     *
     * @param deadlineName    The name of the deadline.
     * @param deadlineId      The id of the deadline.
     * @param deadlineMessage The message of the deadline.
     * @return The created span.
     */
    Span createScheduleSpan(String deadlineName, String deadlineId, DeadlineMessage deadlineMessage);

    /**
     * Creates a span that represents the cancellation of a specific deadline.
     *
     * @param deadlineName The name of the deadline.
     * @param deadlineId   The id of the deadline.
     * @return The created span.
     */
    Span createCancelScheduleSpan(String deadlineName, String deadlineId);

    /**
     * Creates a span that represents the cancellation of all deadlines with a certain name.
     *
     * @param deadlineName The name of the deadlines.
     * @return The created span.
     */
    Span createCancelAllSpan(String deadlineName);

    /**
     * Creates a span that represents the cancellation of all deadlines with a certain name within a certain scope.
     *
     * @param deadlineName    The name of the deadlines.
     * @param scopeDescriptor The scope descriptor of the deadlines.
     * @return The created span.
     */
    Span createCancelAllWithinScopeSpan(String deadlineName, ScopeDescriptor scopeDescriptor);

    /**
     * Creates a span that represents the execution of a deadline.
     *
     * @param deadlineName    The name of the deadline.
     * @param deadlineId      The id of the deadline.
     * @param deadlineMessage The message of the deadline.
     * @return The created span.
     */
    Span createExecuteSpan(String deadlineName, String deadlineId, DeadlineMessage deadlineMessage);

}
