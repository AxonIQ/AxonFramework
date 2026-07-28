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

package org.axonframework.hunt.harness;

import org.axonframework.messaging.eventhandling.processing.streaming.token.store.TokenStore;

import java.util.Objects;

/**
 * The one token store a run's nodes compete over, handed out one view per node.
 * <p>
 * Ownership only means anything when several nodes address the same rows with different identities, so a backend
 * returns a factory rather than a store: every node asks for its own view, carrying its own node identity, and all of
 * them address the same underlying table. A store that arbitrates nothing simply hands the same instance to everyone,
 * which is honest rather than convenient -- it is exactly why the in-heap store cannot express a claim.
 *
 * @author Stefan Dragisic
 * @since 5.3.0
 */
public interface TokenStores extends AutoCloseable {

    /**
     * Returns the view of the shared store that the named node claims through.
     *
     * @param nodeId the node's identity, which is the owner a claim is recorded under
     * @return the node's view of the run's token store
     */
    TokenStore forNode(String nodeId);

    /**
     * Returns the named node's view of the shared store, configured to treat a claim as expired
     * {@code clockSkew} earlier than the run's claim timeout says it should.
     * <p>
     * <b>This is how the suite emulates a node whose clock runs ahead, and it is exact rather than approximate.</b>
     * Whether a claim has expired is decided by {@code timestamp + claimTimeout < now}. A node whose clock is
     * {@code delta} ahead reads {@code now + delta}, so it considers a claim expired exactly when
     * {@code timestamp + (claimTimeout - delta) < now} -- which is what a store view configured with a claim timeout
     * shortened by {@code delta} decides. The two are the same inequality, so no decorator and no clock substitution is
     * needed; the store's own per-instance setting carries it.
     * <p>
     * <b>What this does not model.</b> It skews only the comparison the node <em>performs</em>, not the timestamps the
     * node <em>writes</em>: a real clock that runs ahead also stamps its own claims into the future, which would make
     * other nodes steal from it later rather than sooner. Writing a skewed timestamp is unreachable, because the
     * framework stamps a claim from a process-global clock that every node in one virtual machine shares (finding F-4).
     * The emulation therefore reproduces one direction of skew -- a node that steals other nodes' claims early -- and
     * not the other. Every scenario using it says so, and the ownership oracle's tolerance is the same {@code delta},
     * so the arithmetic the oracle applies and the arithmetic the store applies come from one declared number.
     * <p>
     * The default ignores the skew, which is correct for a store with no notion of expiry: there is no comparison to
     * skew.
     *
     * @param nodeId    the node's identity, which is the owner a claim is recorded under
     * @param clockSkew how far ahead of the rest of the cluster this node's clock is emulated to run;
     *                  {@link java.time.Duration#ZERO} for a node in step with everybody else
     * @return the node's view of the run's token store
     */
    default TokenStore forNode(String nodeId, java.time.Duration clockSkew) {
        return forNode(nodeId);
    }

    /**
     * Releases whatever the run's token store held. The default does nothing, which is right for a store that lives
     * only in the heap.
     */
    @Override
    default void close() {
        // Nothing to release for an in-heap store.
    }

    /**
     * Hands the same store to every node.
     * <p>
     * Correct only for a store with no notion of an owner: with one instance there is one identity, so nothing can be
     * arbitrated and every ownership assertion made against it holds vacuously.
     *
     * @param store the store every node shares
     * @return a factory returning that store for any node
     */
    static TokenStores shared(TokenStore store) {
        Objects.requireNonNull(store, "The store cannot be null.");
        return nodeId -> store;
    }
}
