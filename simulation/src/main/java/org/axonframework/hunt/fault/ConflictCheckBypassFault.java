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
 * Commits an append without enforcing the condition it was made under.
 * <p>
 * This is the defect the whole suite exists to catch, expressed as a fault instead of as a patch: a store that
 * accepts a write whose consistency boundary has already been invalidated. Two writers that read the same balance
 * both commit, and the ledger loses money that nothing in the system will report.
 * <p>
 * Its purpose is to test the tests. An oracle that stays green while this fault is installed is not an oracle, and a
 * scenario that has never been observed failing is decoration. Running a scenario under this fault and watching it go
 * red is what earns the right to trust it when it is green.
 * <p>
 * The batch the store ends up holding is exactly the batch that was offered, so the run is still judged rather than
 * excused: what changed is whether the append should have been allowed at all.
 *
 * @author Stefan Dragisic
 * @since 5.3.0
 */
public final class ConflictCheckBypassFault implements Fault {

    private final int everyNth;
    private final AtomicLong seen = new AtomicLong();
    private volatile @Nullable StoreHook hook;

    /**
     * Creates the fault.
     *
     * @param everyNth how many commits pass between bypasses; {@code 1} bypasses the check on every commit
     */
    public ConflictCheckBypassFault(int everyNth) {
        if (everyNth < 1) {
            throw new IllegalArgumentException("The everyNth must be at least one, but was " + everyNth + ".");
        }
        this.everyNth = everyNth;
    }

    @Override
    public String kind() {
        return "conflict-check-bypass";
    }

    @Override
    public Map<String, String> parameters() {
        return Map.of("everyNth", String.valueOf(everyNth));
    }

    @Override
    public void activate(FaultSite site, FaultEvidence evidence) {
        StoreHook installed = new StoreHook() {
            @Override
            public CommitAction onCommit(AppendAttempt attempt) {
                if (attempt.batchSize() == 0 || seen.incrementAndGet() % everyNth != 0) {
                    return CommitAction.proceed();
                }
                evidence.fired(attempt.describe());
                return CommitAction.bypassCondition();
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
