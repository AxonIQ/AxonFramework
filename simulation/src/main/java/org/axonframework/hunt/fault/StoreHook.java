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

/**
 * The seam a fault installs into the store wrapper.
 * <p>
 * The wrapper calls every installed hook around each append and each commit. A hook may delay the call, and it may
 * ask for the commit to be interfered with by returning something other than {@link CommitAction#proceed()}. It never
 * touches the store itself, so a fault stays a description of what should go wrong rather than an implementation of
 * how.
 * <p>
 * Hooks are called from whichever workload thread issued the append, so implementations must be thread-safe.
 *
 * @author Stefan Dragisic
 * @since 5.3.0
 */
public interface StoreHook {

    /**
     * Called before the append reaches the store.
     * <p>
     * The default does nothing. A latency fault sleeps here; a fault that wants the append never to be attempted
     * throws from here.
     *
     * @param attempt what is about to be appended
     */
    default void beforeAppend(AppendAttempt attempt) {
        // No interference by default.
    }

    /**
     * Called once the store has taken the append but before the framework has committed anything.
     * <p>
     * <b>This is the only point at which a fault can hold a real transaction open, and where it sits matters.</b> On a
     * store reached through a transaction on the processing context, the engine takes its positions and writes its rows
     * while it is being <em>asked</em> to append -- the aggregate-based engine flushes inside {@code appendEvents}
     * precisely so that a constraint violation arrives there -- and the transaction that makes them readable commits
     * later, in the framework's commit phase. Delaying here therefore leaves a row written, a position consumed, and
     * nothing visible: which is exactly the hole a concurrent reader has to come back for.
     * <p>
     * Delaying at {@link #onCommit(AppendAttempt)} does not do this, and measuring that cost an afternoon. The
     * framework's commit phase runs its registered actions with no ordering between them, so the database transaction's
     * commit and the append transaction's own {@code commit()} are concurrent: an event was measured readable 1877ms
     * before the {@code commit()} of the append that produced it was even entered. A delay there is a delay after
     * durability, not before it.
     * <p>
     * The default does nothing.
     *
     * @param attempt what the store has just taken
     */
    default void afterAppend(AppendAttempt attempt) {
        // No interference by default.
    }

    /**
     * Called when the append transaction is about to commit.
     * <p>
     * On a store whose {@code commit()} does the work -- the in-heap engine -- this is the point of no return. On a store
     * whose transaction is on the processing context it is not: that store's {@code commit()} does nothing, and the
     * transaction commits concurrently with it. Use {@link #afterAppend(AppendAttempt)} for anything that has to happen
     * while the append is still undone.
     *
     * @param attempt what is about to be committed
     * @return what the wrapper should do with the batch; {@link CommitAction#proceed()} by default
     */
    default CommitAction onCommit(AppendAttempt attempt) {
        return CommitAction.proceed();
    }

    /**
     * Called after the commit has succeeded, when the framework asks the transaction for its consistency marker.
     * <p>
     * This is the one phase in which a failure cannot un-append anything: the store has already published the batch.
     * A fault answering {@code true} therefore models an infrastructure failure strictly after the point of no
     * return, which is exactly the window in which the framework's own error handling calls
     * {@code AppendTransaction.rollback()} on a transaction that has already committed.
     *
     * @param attempt what has just been committed
     * @return {@code true} to make the marker calculation fail; {@code false} by default
     */
    default boolean failsAfterCommit(AppendAttempt attempt) {
        return false;
    }
}
