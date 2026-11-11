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

package org.axonframework.extension.springboot;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties of the transactions that Axon Framework starts for units of work, under the
 * {@code axon.transaction} prefix.
 *
 * @author John Hendrikx
 * @since 5.4.2
 */
@ConfigurationProperties(prefix = "axon.transaction")
public class TransactionProperties {

    /**
     * Whether a unit of work that starts while a transaction is already active may take part in that transaction.
     * <p>
     * Defaults to {@code false}: such a unit of work fails.
     * <p>
     * <b>Setting this to {@code true} is not a supported configuration.</b> A unit of work that takes part in an
     * active transaction does not own the commit, which has several problems:
     * <ul>
     *     <li>Its commit does not commit anything, so it reports success while the active transaction can still roll
     *     its work back.</li>
     *     <li>The actions it runs after its commit run before the active transaction is committed. Event stores that
     *     finalize or publish appended events after the commit do so too early, so events can stay invisible to
     *     streams, or be skipped by them.</li>
     *     <li>Subscription query updates, callbacks of event processors and monitoring that run after the commit
     *     report work that is not committed yet.</li>
     * </ul>
     * Start units of work outside of transactions instead.
     */
    private boolean allowJoiningActiveTransaction;

    /**
     * Returns whether a unit of work may take part in a transaction that is already active.
     *
     * @return {@code true} if allowed, {@code false} if such a unit of work fails
     */
    public boolean isAllowJoiningActiveTransaction() {
        return allowJoiningActiveTransaction;
    }

    /**
     * Sets whether a unit of work may take part in a transaction that is already active.
     *
     * @param allowJoiningActiveTransaction {@code true} to allow, {@code false} to make such a unit of work fail
     */
    public void setAllowJoiningActiveTransaction(boolean allowJoiningActiveTransaction) {
        this.allowJoiningActiveTransaction = allowJoiningActiveTransaction;
    }
}
