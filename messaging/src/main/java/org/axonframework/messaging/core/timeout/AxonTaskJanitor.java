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

package org.axonframework.messaging.core.timeout;

import org.axonframework.common.AxonThreadFactory;
import org.axonframework.common.configuration.ComponentDefinition;
import org.axonframework.common.configuration.Configuration;
import org.axonframework.common.lifecycle.Phase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Container of unique {@link ScheduledExecutorService} and {@link Logger} instances for the
 * {@link AxonTimeLimitedTask}.
 * <p>
 * {@link #INSTANCE} is a JVM-wide fallback, intended for standalone/manual construction of {@link AxonTimeLimitedTask}
 * or {@link TimeoutUnitOfWorkFactory} outside of a {@link Configuration}. It is not tied to any {@code Configuration}'s
 * lifecycle, so callers using it directly are responsible for its lifecycle themselves. Message handling and unit of
 * work timeouts driven through an Axon {@code Configuration} instead use {@link #executor()} to obtain a
 * {@link ComponentDefinition} for an executor scoped to, and shut down with, that specific {@code Configuration} - so
 * that shutting down one {@code Configuration} can never affect timeout enforcement in another {@code Configuration}
 * sharing the same JVM.
 *
 * @author Mitchell Herrijgers
 * @see AxonTimeLimitedTask
 * @since 4.11.0
 */
public class AxonTaskJanitor {

    /**
     * The name under which the {@link Configuration}-scoped {@link ScheduledExecutorService}, defined by
     * {@link #executor()}, is known in the {@link Configuration}.
     */
    public static final String EXECUTOR_COMPONENT_NAME = "AxonTaskJanitorScheduledExecutorService";

    /**
     * Unique instances of the {@link ScheduledExecutorService} for the {@link AxonTimeLimitedTask} to schedule warnings
     * and interrupts.
     */
    protected static final ScheduledExecutorService INSTANCE = createJanitorExecutorService();

    /**
     * Unique instance of the {@link Logger} for the {@link AxonTimeLimitedTask} to log warnings and errors.
     */
    public static final Logger LOGGER = LoggerFactory.getLogger("axon-janitor");

    private AxonTaskJanitor() {
        // Utility class
    }

    /**
     * Creates a {@link ComponentDefinition} for a {@link ScheduledExecutorService}, named
     * {@link #EXECUTOR_COMPONENT_NAME}, scoped to whichever {@link Configuration} it is registered with (for example
     * through
     * {@link org.axonframework.common.configuration.ComponentRegistry#registerIfNotPresent(ComponentDefinition)}).
     * <p>
     * The defined executor is created lazily, on first use, and is shut down automatically when the owning
     * {@code Configuration} shuts down. Since the executor is scoped to a single {@code Configuration}, shutting that
     * {@code Configuration} down can never affect timeout enforcement in another {@code Configuration} sharing the same
     * JVM.
     *
     * @return a {@link ComponentDefinition} for a {@link Configuration}-scoped {@link ScheduledExecutorService}
     */
    public static ComponentDefinition<ScheduledExecutorService> executor() {
        return ComponentDefinition.ofTypeAndName(ScheduledExecutorService.class, EXECUTOR_COMPONENT_NAME)
                                  .withBuilder(c -> createJanitorExecutorService())
                                  .onShutdown(Phase.EXTERNAL_CONNECTIONS - 10, AxonTaskJanitor::gracefulShutdown);
    }

    /**
     * Creates the ScheduledExecutorService used for scheduling the interrupting task. It only has one thread as the
     * load is very low. Cancelling the tasks will clean it up to reduce memory pressure.
     *
     * @return The ScheduledExecutorService
     */
    private static ScheduledThreadPoolExecutor createJanitorExecutorService() {
        ScheduledThreadPoolExecutor janitor = new ScheduledThreadPoolExecutor(1, new AxonThreadFactory("axon-janitor"));
        // Clean up tasks in the queue when canceled. Performance is equal but reduces memory pressure.
        janitor.setRemoveOnCancelPolicy(true);
        return janitor;
    }

    private static void gracefulShutdown(ScheduledExecutorService executor) {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                LOGGER.warn("The janitor's executor did not terminate within 5 seconds. Forcing shutdown.");
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            LOGGER.warn("Interrupted while awaiting the janitor's executor termination. Forcing shutdown.");
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
