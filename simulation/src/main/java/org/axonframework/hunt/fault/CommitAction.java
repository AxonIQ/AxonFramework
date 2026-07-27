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

import java.util.Objects;

/**
 * What the fault-injecting store wrapper should do with a batch that is about to be committed.
 * <p>
 * A {@link StoreHook} returns one of these instead of manipulating the store itself, which keeps every fault free of
 * framework types and makes a fault testable without a store.
 *
 * @param kind      what to do with the batch
 * @param keepCount for {@link Kind#PREFIX}, how many events of the batch to store; ignored otherwise
 * @author Stefan Dragisic
 * @since 5.3.0
 */
public record CommitAction(Kind kind, int keepCount) {

    private static final CommitAction PROCEED = new CommitAction(Kind.PROCEED, Integer.MAX_VALUE);
    private static final CommitAction REJECT = new CommitAction(Kind.REJECT, 0);
    private static final CommitAction VANISH = new CommitAction(Kind.VANISH, 0);
    private static final CommitAction DUPLICATE = new CommitAction(Kind.DUPLICATE, Integer.MAX_VALUE);
    private static final CommitAction BYPASS_CONDITION = new CommitAction(Kind.BYPASS_CONDITION, Integer.MAX_VALUE);

    /**
     * Compact constructor rejecting a missing kind and a negative keep count.
     */
    public CommitAction {
        Objects.requireNonNull(kind, "The kind cannot be null.");
        if (keepCount < 0) {
            throw new IllegalArgumentException("The keepCount cannot be negative, but was " + keepCount + ".");
        }
    }

    /**
     * Commits the batch as the caller asked.
     *
     * @return the do-nothing action
     */
    public static CommitAction proceed() {
        return PROCEED;
    }

    /**
     * Fails the commit without storing anything, as an infrastructure failure rather than a consistency conflict.
     *
     * @return the rejecting action
     */
    public static CommitAction reject() {
        return REJECT;
    }

    /**
     * Reports the commit as successful while storing nothing: the write-then-vanish window.
     *
     * @return the vanishing action
     */
    public static CommitAction vanish() {
        return VANISH;
    }

    /**
     * Stores the batch twice, as an at-least-once store does when a retried append's first attempt had landed.
     *
     * @return the duplicating action
     */
    public static CommitAction duplicate() {
        return DUPLICATE;
    }

    /**
     * Stores only the first {@code keepCount} events of the batch and reports the commit as successful.
     *
     * @param keepCount how many events of the batch to store
     * @return the truncating action
     */
    public static CommitAction prefix(int keepCount) {
        return new CommitAction(Kind.PREFIX, keepCount);
    }

    /**
     * Stores the batch exactly as offered, but without enforcing the condition it was offered under.
     * <p>
     * This models a store whose consistency check is broken, and it is the one interference that leaves the store's
     * contents exactly as the caller asked for while making them wrong. It is how the suite proves its own conflict
     * oracle can fail: a run under it must go red, and an oracle that stays green under it is not an oracle.
     *
     * @return the check-bypassing action
     */
    public static CommitAction bypassCondition() {
        return BYPASS_CONDITION;
    }

    /**
     * Indicates whether this action leaves the store holding something other than what was offered.
     * <p>
     * A perturbed store no longer matches the reference model's replay of the same history, so a checker that replays
     * against the model must downgrade its verdict rather than report the difference as a violation.
     *
     * @return {@code true} when the action changes what the store ends up holding
     */
    public boolean perturbsStoreContents() {
        return kind == Kind.VANISH || kind == Kind.DUPLICATE || kind == Kind.PREFIX;
    }

    /**
     * The kinds of interference a hook may ask for.
     *
     * @author Stefan Dragisic
     * @since 5.3.0
     */
    public enum Kind {

        /**
         * Commit the batch unchanged.
         */
        PROCEED,

        /**
         * Fail the commit with an injected infrastructure error; nothing is stored.
         */
        REJECT,

        /**
         * Report success but store nothing.
         */
        VANISH,

        /**
         * Store the batch twice.
         */
        DUPLICATE,

        /**
         * Store only a prefix of the batch.
         */
        PREFIX,

        /**
         * Store the whole batch, but without enforcing the condition it was offered under.
         */
        BYPASS_CONDITION
    }
}
