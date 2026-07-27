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
 * Makes the store refuse to commit.
 * <p>
 * This is the store being unavailable, not the protocol saying no. The distinction is deliberate and is carried all
 * the way into the history: the refusal is recorded under its own error, so a checker replaying against the reference
 * model does not read an infrastructure failure as a consistency conflict and report the run as broken.
 * <p>
 * What it exercises is the caller's side of a failed write: whether the unit of work rolls back cleanly, whether the
 * events stay invisible, and whether the workload's own accounting stays honest about what did not happen.
 *
 * @author Stefan Dragisic
 * @since 5.3.0
 */
public final class AppendRejectionFault implements Fault {

    private final int everyNth;
    private final AtomicLong seen = new AtomicLong();
    private volatile @Nullable StoreHook hook;

    /**
     * Creates the fault.
     *
     * @param everyNth how many commits pass between refusals; {@code 1} refuses every commit
     */
    public AppendRejectionFault(int everyNth) {
        if (everyNth < 1) {
            throw new IllegalArgumentException("The everyNth must be at least one, but was " + everyNth + ".");
        }
        this.everyNth = everyNth;
    }

    @Override
    public String kind() {
        return "append-rejection";
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
                if (seen.incrementAndGet() % everyNth != 0) {
                    return CommitAction.proceed();
                }
                evidence.fired(attempt.describe());
                return CommitAction.reject();
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
