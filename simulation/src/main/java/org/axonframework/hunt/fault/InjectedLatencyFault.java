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

import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * Makes the store slow.
 * <p>
 * Latency is the cheapest way to widen a race. Every window between reading a consistency marker and appending
 * against it is measured in microseconds under no load; adding a delay in the middle of that window makes the
 * interleaving where a competitor commits in between the common case instead of the rare one.
 * <p>
 * Nothing about the store's contents changes, so a run under this fault can still be judged against the reference
 * model.
 *
 * @author Stefan Dragisic
 * @since 5.3.0
 */
public final class InjectedLatencyFault implements Fault {

    private final Duration delay;
    private volatile @Nullable StoreHook hook;

    /**
     * Creates the fault.
     *
     * @param delay how long every append is held before it reaches the store
     */
    public InjectedLatencyFault(Duration delay) {
        this.delay = Objects.requireNonNull(delay, "The delay cannot be null.");
        if (delay.isNegative()) {
            throw new IllegalArgumentException("The delay cannot be negative, but was " + delay + ".");
        }
    }

    @Override
    public String kind() {
        return "injected-latency";
    }

    @Override
    public Map<String, String> parameters() {
        return Map.of("delayMs", String.valueOf(delay.toMillis()));
    }

    @Override
    public void activate(FaultSite site, FaultEvidence evidence) {
        StoreHook installed = new StoreHook() {
            @Override
            public void beforeAppend(AppendAttempt attempt) {
                try {
                    Thread.sleep(delay.toMillis(), delay.toNanosPart() % 1_000_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                evidence.fired(attempt.participant());
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
