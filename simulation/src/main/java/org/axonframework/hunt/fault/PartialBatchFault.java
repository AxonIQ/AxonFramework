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
 * Stores only the front of a batch and reports the whole thing as committed.
 * <p>
 * A multi-event append is the framework's unit of atomicity, and a workload that appends a withdrawal and a deposit
 * together is relying on it: half of that pair is money destroyed. This fault produces exactly that half, which is
 * what makes the ledger's conservation law an oracle rather than a slogan.
 * <p>
 * The store ends up holding less than the workload offered, so a run under this fault cannot be judged against the
 * reference model.
 *
 * @author Stefan Dragisic
 * @since 5.3.0
 */
public final class PartialBatchFault implements Fault {

    private final int keepCount;
    private final int everyNth;
    private final AtomicLong seen = new AtomicLong();
    private volatile @Nullable StoreHook hook;

    /**
     * Creates the fault.
     *
     * @param keepCount how many events of a truncated batch survive
     * @param everyNth  how many commits pass between truncations; {@code 1} truncates every multi-event commit
     */
    public PartialBatchFault(int keepCount, int everyNth) {
        if (keepCount < 0) {
            throw new IllegalArgumentException("The keepCount cannot be negative, but was " + keepCount + ".");
        }
        if (everyNth < 1) {
            throw new IllegalArgumentException("The everyNth must be at least one, but was " + everyNth + ".");
        }
        this.keepCount = keepCount;
        this.everyNth = everyNth;
    }

    @Override
    public String kind() {
        return "partial-batch";
    }

    @Override
    public Map<String, String> parameters() {
        return Map.of("keepCount", String.valueOf(keepCount), "everyNth", String.valueOf(everyNth));
    }

    @Override
    public boolean perturbsStoreContents() {
        return true;
    }

    @Override
    public void activate(FaultSite site, FaultEvidence evidence) {
        StoreHook installed = new StoreHook() {
            @Override
            public CommitAction onCommit(AppendAttempt attempt) {
                if (attempt.batchSize() <= keepCount || seen.incrementAndGet() % everyNth != 0) {
                    return CommitAction.proceed();
                }
                evidence.fired(attempt.describe());
                return CommitAction.prefix(keepCount);
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
