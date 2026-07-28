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

package org.axonframework.hunt.fault;

import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Fails the marker calculation the framework asks for once the commit has already succeeded.
 * <p>
 * This is the latest of the three points at which a transaction can die, and the only one past the point of no
 * return: the store has published the batch, so those events are legitimately visible and stay visible. What the
 * failure exercises is the framework's error handling, which reacts by calling
 * {@code AppendTransaction.rollback()} on a transaction that has already committed.
 * <p>
 * The arm exists to establish that the visibility guarantee is not read too strongly in either direction. Events
 * committed before the failure must remain observable, and no oracle may report them as having been rolled back
 * merely because a rollback was requested after the fact.
 *
 * @author Stefan Dragisic
 * @since 5.3.0
 */
public final class AfterCommitFailureFault implements Fault {

    private final int everyNth;
    private final AtomicLong seen = new AtomicLong();
    private volatile @Nullable StoreHook hook;

    /**
     * Creates the fault.
     *
     * @param everyNth how often to fail the marker calculation; one in {@code everyNth} committed transactions
     */
    public AfterCommitFailureFault(int everyNth) {
        if (everyNth < 1) {
            throw new IllegalArgumentException("The everyNth must be at least one, but was " + everyNth + ".");
        }
        this.everyNth = everyNth;
    }

    @Override
    public String kind() {
        return "after-commit-failure";
    }

    @Override
    public Map<String, String> parameters() {
        return Map.of("everyNth", String.valueOf(everyNth));
    }

    @Override
    public void activate(FaultSite site, FaultEvidence evidence) {
        StoreHook installed = new StoreHook() {
            @Override
            public boolean failsAfterCommit(AppendAttempt attempt) {
                if (attempt.batchSize() == 0 || seen.incrementAndGet() % everyNth != 0) {
                    return false;
                }
                evidence.fired(attempt.describe());
                return true;
            }
        };
        hook = installed;
        site.installStoreHook(installed);
    }

    @Override
    public void deactivate(FaultSite site) {
        StoreHook installed = hook;
        if (installed != null) {
            site.removeStoreHook(installed);
            hook = null;
        }
    }
}
