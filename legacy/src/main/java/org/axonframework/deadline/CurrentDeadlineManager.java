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

import org.axonframework.messaging.core.Context;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;

import java.util.Objects;

/**
 * Holds the {@link Context.ResourceKey} under which a {@link ProcessingContext}-scoped {@link DeadlineManager} is
 * registered, and resolves it.
 * <p>
 * A {@code DeadlineManager} instance registered under {@link #RESOURCE_KEY} is bound to the {@link ProcessingContext}
 * it was registered for: scheduling or cancelling a deadline through it defers the actual call until that context
 * reaches its prepare-commit phase, matching the Axon Framework 4 behaviour of a deadline call made while a
 * {@code UnitOfWork} is active. A {@code DeadlineManager} obtained any other way (field or constructor injection) has
 * no such binding and runs every call immediately.
 * <p>
 * Unlike {@link org.axonframework.messaging.core.CurrentScope}, there is no sensible fallback when nothing is
 * registered under this key: a silent no-op {@code DeadlineManager} would lose deadlines rather than fail loudly, so
 * {@link #forContext(ProcessingContext)} fails instead of falling back, mirroring
 * {@code SagaLifecycle.forContext(ProcessingContext)}'s fail-fast precedent rather than {@code CurrentScope}'s
 * null-object one.
 *
 * @author Jakob Hatzl
 * @since 5.4.0
 */
public final class CurrentDeadlineManager {

    /**
     * The {@link Context.ResourceKey} under which the {@link ProcessingContext}-scoped {@link DeadlineManager} is
     * registered.
     */
    public static final Context.ResourceKey<DeadlineManager> RESOURCE_KEY =
            Context.ResourceKey.withLabel("currentDeadlineManager");

    private CurrentDeadlineManager() {
        // Utility class
    }

    /**
     * Retrieves the {@link DeadlineManager} registered for the given {@code context}.
     *
     * @param context the {@link ProcessingContext} to retrieve the active {@link DeadlineManager} for
     * @return the {@link DeadlineManager} active for the given {@code context}
     * @throws IllegalStateException if no {@link DeadlineManager} is registered for the given {@code context}
     */
    public static DeadlineManager forContext(ProcessingContext context) {
        Objects.requireNonNull(context, "ProcessingContext may not be null");
        DeadlineManager deadlineManager = context.getResource(RESOURCE_KEY);
        if (deadlineManager == null) {
            throw new IllegalStateException(
                    "No DeadlineManager is active for the given ProcessingContext. A DeadlineManager is only "
                            + "available while a Saga instance is handling an event."
            );
        }
        return deadlineManager;
    }
}
