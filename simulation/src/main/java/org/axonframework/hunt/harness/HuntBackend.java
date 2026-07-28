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

import org.axonframework.eventsourcing.eventstore.EventStorageEngine;
import org.axonframework.messaging.core.unitofwork.transaction.TransactionManager;
import org.jspecify.annotations.Nullable;

import java.util.Comparator;
import java.util.List;
import java.util.ServiceLoader;

/**
 * The store a scenario is driven against.
 * <p>
 * This is the suite's only backend seam, and it is the whole attribution strategy. A framework is a library, so the
 * thing under test is really the library crossed with a store protocol. Running the identical scenario against every
 * backend is what turns "it broke" into "it broke everywhere, so it is the framework" or "it broke on one, so it is
 * that adapter".
 * <p>
 * Implementations are found through the {@link ServiceLoader}, so adding a backend adds a class and one line in
 * {@code META-INF/services/org.axonframework.hunt.harness.HuntBackend}. Every existing scenario then runs against it
 * with no change at all.
 *
 * @author Stefan Dragisic
 * @since 5.3.0
 */
public interface HuntBackend {

    /**
     * Returns the backend's name, as it appears in a scenario record, in the history header, and in a per-backend
     * verdict vector.
     *
     * @return the backend name, for example {@code in-memory}
     */
    String name();

    /**
     * Creates a storage engine for one run. Never shared between runs.
     *
     * @return a fresh, empty storage engine
     */
    EventStorageEngine createEngine();

    /**
     * Releases anything the run's engine held. The default does nothing, which is right for a store that lives only
     * in the heap.
     *
     * @param engine the engine this backend created
     */
    default void release(EventStorageEngine engine) {
        // Nothing to release for an in-heap store.
    }

    /**
     * Creates the token store the run's nodes claim their segments through, one view per node.
     * <p>
     * The default hands every node the framework's in-heap store, which has no owner, no timestamp and no expiry, so
     * every node's claim always succeeds. That is correct for a single-node run and vacuous for any other, which is
     * why {@link #arbitratesTokenClaims()} exists alongside it: an ownership oracle must be able to tell the two
     * apart rather than report a store that arbitrates nothing as a store that never broke.
     *
     * @param runId        identifies this run, so two runs never address the same underlying store
     * @param claimTimeout how long a claim survives without extension; a store setting, so it cannot travel through
     *                     the processor configuration the way the run's other compressed timings do
     * @return a factory handing each node its own view of one shared token store
     */
    default TokenStores createTokenStores(String runId, java.time.Duration claimTimeout) {
        return TokenStores.shared(
                new org.axonframework.messaging.eventhandling.processing.streaming.token.store.inmemory.InMemoryTokenStore());
    }

    /**
     * Indicates whether this store implements the Dynamic Consistency Boundary protocol the suite's reference model
     * describes.
     * <p>
     * Not every store does. The aggregate-based engine accepts at most one tag per event and reads it as an aggregate
     * identifier, and its conflict check is the database's unique constraint on an aggregate's sequence number rather
     * than a scan over a consistency marker. Replaying such a history against the reference model would report the
     * difference in protocol as a defect on every append, so the model oracle names itself inexpressible instead --
     * which is a recorded "not applicable" in the backend vector rather than a quiet pass.
     *
     * @return {@code true} when the store's append condition is a boundary over tags and a marker
     */
    default boolean speaksDynamicConsistencyBoundaries() {
        return true;
    }

    /**
     * Returns the transaction manager every unit of work of the run must be wrapped in, or {@code null} when the store
     * needs none.
     * <p>
     * <b>This is not an optimisation, and a persistent store cannot opt out of it.</b> A storage engine that speaks to
     * a database asks the processing context for the transactional executor to use, so without one attached the very
     * first append fails. Attaching it also decides <em>when</em> an append becomes durable: with the run's transaction
     * on the context, the append commits in the framework's commit phase, which is where the framework's own
     * visibility guarantee says it commits. The alternative -- giving each store call its own transaction -- makes an
     * append durable the moment the engine is handed the events, before the framework has decided whether to commit at
     * all, and every visibility oracle then reports the harness's wiring as a framework defect.
     * <p>
     * The default returns {@code null}, which is right for a store that lives in the heap and has no transaction to
     * join.
     *
     * @param engine the engine this backend created, in case the manager has to share its resources
     * @return the transaction manager to wrap every unit of work in, or {@code null} for a store with no transactions
     */
    default @Nullable TransactionManager transactionManager(EventStorageEngine engine) {
        return null;
    }

    /**
     * Indicates whether this backend's token store decides who owns a segment.
     * <p>
     * Recorded in the history header so that an ownership oracle can hold vacuously, and say so, on a store that
     * implements no ownership at all instead of pretending it verified something.
     *
     * @return {@code true} when the store implements the claim algebra, {@code false} when it grants every claim
     */
    default boolean arbitratesTokenClaims() {
        return false;
    }

    /**
     * Returns every registered backend, ordered by name.
     *
     * @return the registered backends
     */
    static List<HuntBackend> discover() {
        return ServiceLoader.load(HuntBackend.class, HuntBackend.class.getClassLoader())
                            .stream()
                            .map(ServiceLoader.Provider::get)
                            .sorted(Comparator.comparing(HuntBackend::name))
                            .toList();
    }

    /**
     * Returns the backend with the given name.
     *
     * @param name the backend's name
     * @return the backend
     * @throws IllegalArgumentException if no backend with that name is registered
     */
    static HuntBackend byName(String name) {
        return discover().stream()
                         .filter(backend -> backend.name().equals(name))
                         .findFirst()
                         .orElseThrow(() -> new IllegalArgumentException(
                                 "No backend named [" + name + "] is registered; found "
                                         + discover().stream().map(HuntBackend::name).toList() + "."));
    }
}
