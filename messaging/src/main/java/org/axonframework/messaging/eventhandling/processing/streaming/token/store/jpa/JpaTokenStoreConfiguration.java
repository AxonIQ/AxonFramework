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

package org.axonframework.messaging.eventhandling.processing.streaming.token.store.jpa;

import jakarta.persistence.LockModeType;
import org.axonframework.messaging.eventhandling.processing.streaming.token.TrackingToken;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.TokenStore;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.time.temporal.TemporalAmount;

import static org.axonframework.common.BuilderUtils.assertNonEmpty;
import static org.axonframework.common.BuilderUtils.assertNonNull;

/**
 * Configuration for the {@link JpaTokenStore}.
 * <p>
 * Can be used to modify the {@link JpaTokenStore}'s settings.
 *
 * @param loadingLockMode The lock mode to use when retrieving tokens from the underlying store.
 * @param claimTimeout    A timeout specifying the time after which this process will force a claim.
 * @param nodeId          The id as a {@link String} to identify ownership of the tokens.
 * @author Jens Mayer
 * @since 5.0.0
 */
public record JpaTokenStoreConfiguration(
        LockModeType loadingLockMode,
        TemporalAmount claimTimeout,
        String nodeId
) {

    /**
     * A {@code JpaTokenStoreConfiguration} instance with the following default values:
     * <ul>
     *     <li>The {@code loadingLockMode} defaults to {@link LockModeType#PESSIMISTIC_WRITE}</li>
     *     <li>The {@code claimTimeout} defaults to 10 seconds</li>
     *     <li>The {@code nodeId} defaults to the name of the managed bean for the runtime system of the Java virtual machine</li>
     * </ul>
     */
    public static JpaTokenStoreConfiguration DEFAULT = new JpaTokenStoreConfiguration(
            LockModeType.PESSIMISTIC_WRITE, Duration.ofSeconds(10), ManagementFactory.getRuntimeMXBean().getName()
    );

    /**
     * Compact constructor validating that the given {@code nodeId} is non-empty and non-null.
     */
    @SuppressWarnings("MissingJavadoc")
    public JpaTokenStoreConfiguration {
        assertNonEmpty(nodeId, "The nodeId may not be empty.");
    }

    /**
     * The {@link LockModeType} to use when loading tokens from the underlying database.
     * <p>
     * Defaults to {@code LockModeType.PESSIMISTIC_WRITE}, to force a write lock, which prevents lock upgrading and
     * potential resulting deadlocks.
     *
     * @param loadingLockMode The lock mode to use when retrieving tokens from the underlying store.
     * @return The configuration itself, for fluent API usage.
     */
    public JpaTokenStoreConfiguration loadingLockMode(LockModeType loadingLockMode) {
        assertNonNull(loadingLockMode, "The loading lock mode may not be null.");
        return new JpaTokenStoreConfiguration(loadingLockMode, claimTimeout, nodeId);
    }

    /**
     * Sets the {@code claimTimeout} specifying the amount of time a process will wait after which this process will
     * force a claim of a {@link TrackingToken}.
     * <p>
     * Thus, if a claim has not been updated for the given {@code claimTimeout}, this process will 'steal' the claim.
     * Defaults to a duration of 10 seconds.
     * <p>
     * The age of a claim is measured by comparing a timestamp another process wrote against this process' clock, so
     * this timeout only means what it says while the clocks of all processes sharing the store agree. See
     * {@link TokenStore} for what a disagreement costs; raising this timeout does not buy tolerance for one.
     *
     * @param claimTimeout A timeout specifying the time after which this process will force a claim.
     * @return The configuration itself, for fluent API usage.
     */
    public JpaTokenStoreConfiguration claimTimeout(TemporalAmount claimTimeout) {
        assertNonNull(claimTimeout, "The claim timeout may not be null.");
        return new JpaTokenStoreConfiguration(loadingLockMode, claimTimeout, nodeId);
    }

    /**
     * Sets the {@code nodeId} to identify ownership of the tokens.
     * <p>
     * Defaults to the name of the managed bean for the runtime system of the Java virtual machine.
     *
     * @param nodeId The id as a {@link String} to identify ownership of the tokens.
     * @return The configuration itself, for fluent API usage.
     */
    public JpaTokenStoreConfiguration nodeId(String nodeId) {
        assertNonEmpty(nodeId, "The nodeId may not be null or empty.");
        return new JpaTokenStoreConfiguration(loadingLockMode, claimTimeout, nodeId);
    }
}