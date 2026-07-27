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
 * Stores a batch twice.
 * <p>
 * This is what an at-least-once store does when a client retries an append whose first attempt had in fact landed.
 * The caller sees one success and the log holds two copies, which is exactly the input a downstream projection is
 * usually assumed never to receive.
 * <p>
 * The store ends up holding more than the workload offered, so a run under this fault cannot be judged against the
 * reference model; what it tests is whether the conservation law downstream survives the duplicate.
 *
 * @author Stefan Dragisic
 * @since 5.3.0
 */
public final class DuplicatedAppendFault implements Fault {

    private final int everyNth;
    private final AtomicLong seen = new AtomicLong();
    private volatile @Nullable StoreHook hook;

    /**
     * Creates the fault.
     *
     * @param everyNth how many commits pass between duplications; {@code 1} duplicates every commit
     */
    public DuplicatedAppendFault(int everyNth) {
        if (everyNth < 1) {
            throw new IllegalArgumentException("The everyNth must be at least one, but was " + everyNth + ".");
        }
        this.everyNth = everyNth;
    }

    @Override
    public String kind() {
        return "duplicated-append";
    }

    @Override
    public Map<String, String> parameters() {
        return Map.of("everyNth", String.valueOf(everyNth));
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
                if (attempt.batchSize() == 0 || seen.incrementAndGet() % everyNth != 0) {
                    return CommitAction.proceed();
                }
                evidence.fired(attempt.describe());
                return CommitAction.duplicate();
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
