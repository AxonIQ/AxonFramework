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

import org.axonframework.hunt.harness.InjectedStoreFailureException;
import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Fails the append itself, so the transaction never reaches a commit at all.
 * <p>
 * The framework hands events to the storage engine in the {@code PREPARE_COMMIT} phase, so a failure here is the
 * earliest of the three points at which a transaction can die. Nothing has been offered to the store, no commit has
 * been started, and the framework has not yet registered its rollback handler for this transaction: the events must
 * therefore be invisible to every consumer and absent from the store, and there is no rollback record to show for it.
 * <p>
 * Distinguishing this from a failure at commit matters, because the two leave different traces and only one of them
 * exercises the rollback path. Folding them into one arm would make it impossible to say which of them a finding came
 * from.
 *
 * @author Stefan Dragisic
 * @since 5.3.0
 */
public final class PrepareCommitFailureFault implements Fault {

    private final int everyNth;
    private final AtomicLong seen = new AtomicLong();
    private volatile @Nullable StoreHook hook;

    /**
     * Creates the fault.
     *
     * @param everyNth how often to fail an append; one in {@code everyNth} attempts dies before reaching the store
     */
    public PrepareCommitFailureFault(int everyNth) {
        if (everyNth < 1) {
            throw new IllegalArgumentException("The everyNth must be at least one, but was " + everyNth + ".");
        }
        this.everyNth = everyNth;
    }

    @Override
    public String kind() {
        return "prepare-commit-failure";
    }

    @Override
    public Map<String, String> parameters() {
        return Map.of("everyNth", String.valueOf(everyNth));
    }

    @Override
    public void activate(FaultSite site, FaultEvidence evidence) {
        StoreHook installed = new StoreHook() {
            @Override
            public void beforeAppend(AppendAttempt attempt) {
                if (attempt.batchSize() == 0 || seen.incrementAndGet() % everyNth != 0) {
                    return;
                }
                evidence.fired(attempt.describe());
                throw new InjectedStoreFailureException(
                        "The append of [" + attempt.describe() + "] was failed by an injected fault before it "
                                + "reached the store.");
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
