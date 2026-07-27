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
 * Reports a commit as successful and stores nothing.
 * <p>
 * This is the acknowledgement that outlives its data: the caller is told the write landed, the read side never sees
 * it, and nothing in the system flags a contradiction. It is the shape of every lost-write incident, and no crash
 * fault produces it, because a crash tells the caller nothing at all.
 * <p>
 * The store ends up holding less than the workload was told it holds, so a run under this fault cannot be judged
 * against the reference model; it is judged by whether anything downstream noticed.
 *
 * @author Stefan Dragisic
 * @since 5.3.0
 */
public final class WriteThenVanishFault implements Fault {

    private final int everyNth;
    private final AtomicLong seen = new AtomicLong();
    private volatile @Nullable StoreHook hook;

    /**
     * Creates the fault.
     *
     * @param everyNth how many commits pass between vanishings; {@code 1} loses every commit
     */
    public WriteThenVanishFault(int everyNth) {
        if (everyNth < 1) {
            throw new IllegalArgumentException("The everyNth must be at least one, but was " + everyNth + ".");
        }
        this.everyNth = everyNth;
    }

    @Override
    public String kind() {
        return "write-then-vanish";
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
                return CommitAction.vanish();
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
