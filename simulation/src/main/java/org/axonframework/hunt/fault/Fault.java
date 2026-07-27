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

import java.util.Map;

/**
 * One thing that can go wrong, expressed as a class.
 * <p>
 * A fault is installed when its window in the {@link FaultSchedule} opens and removed when the window closes. It
 * reaches the running system only through the {@link FaultSite} it is handed, which is why adding a kind adds a class
 * and edits nothing.
 * <p>
 * Every fault must be able to prove it landed. Implementations increment the {@link FaultEvidence} they are given at
 * the moment they actually perturb something, naming the target; the runner writes the resulting count into the
 * history. A declared fault whose count is zero makes the run inconclusive rather than a pass, because a green run
 * under a fault that never fired has verified nothing.
 * <p>
 * Example of a complete fault:
 * <pre>{@code
 * public final class RefuseEverySecondAppend implements Fault {
 *
 *     public String kind() {
 *         return "refuse-every-second-append";
 *     }
 *
 *     public void activate(FaultSite site, FaultEvidence evidence) {
 *         site.installStoreHook(hook = attempt -> {
 *             if (attempt.sequence() % 2 == 1) {
 *                 evidence.fired(attempt.describe());
 *                 return CommitAction.reject();
 *             }
 *             return CommitAction.proceed();
 *         });
 *     }
 * }
 * }</pre>
 *
 * @author Stefan Dragisic
 * @since 5.3.0
 */
public interface Fault {

    /**
     * Returns the fault's stable kind, used in the history and in the fault trace of a violation.
     *
     * @return the kind, in lower-case with words separated by hyphens
     */
    String kind();

    /**
     * Returns what the fault was configured with, so a reader of the history knows what was declared and not only
     * what happened.
     *
     * @return the fault's parameters, rendered flat; empty by default
     */
    default Map<String, String> parameters() {
        return Map.of();
    }

    /**
     * Indicates whether this fault can leave the store holding something other than what the workload appended.
     * <p>
     * A checker that replays a history against the reference model cannot decide a run in which the store was
     * perturbed behind the workload's back: the model would faithfully reproduce what the workload asked for, which
     * is by construction not what the store holds. Such a run is reported inconclusive rather than broken.
     *
     * @return {@code true} when the fault changes what the store ends up holding; {@code false} by default
     */
    default boolean perturbsStoreContents() {
        return false;
    }

    /**
     * Installs the fault. Called when its window opens.
     *
     * @param site     the seams the fault may reach
     * @param evidence the counter the fault increments each time it actually perturbs something
     */
    void activate(FaultSite site, FaultEvidence evidence);

    /**
     * Removes the fault. Called when its window closes, and again at the end of the run, so that the heal phase is
     * genuinely fault-free.
     *
     * @param site the seams the fault reached
     */
    void deactivate(FaultSite site);
}
