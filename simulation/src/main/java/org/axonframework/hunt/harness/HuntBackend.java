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
     * Returns every readable event identifier this store holds, in store order, or {@code null} to let the run ask the
     * engine.
     * <p>
     * <b>A store that was killed cannot be asked through the connections the run was using.</b> The pool holds handles to
     * a process that no longer exists, and whether the pool notices is beside the point: the question "did the store keep
     * what it said it kept" is the one question that must not be answered through the harness's own plumbing, or the
     * answer measures the harness's recovery instead of the store's. A backend that can answer it another way -- a fresh
     * connection and plain SQL -- overrides this and does.
     * <p>
     * The default returns {@code null}, meaning the run reads the store through the engine, which is right for a store
     * that cannot fail independently of the process it lives in.
     *
     * @param engine the engine whose store is to be scanned
     * @return the readable identifiers in store order, or {@code null} to let the run ask the engine
     */
    default @Nullable List<String> readableEventIds(EventStorageEngine engine) {
        return null;
    }

    /**
     * Returns the machinery this store runs on, so a run can break it the way an operator's day breaks it.
     * <p>
     * The default reports {@link StoreInfrastructure#none()}, which is honest for a store that lives in the heap: there
     * is no process to kill, no socket to cut and nothing to freeze. A fault aimed at it records no landing, so the same
     * scenario run here reports a fault that never fired rather than a pass it did not earn.
     *
     * @param engine the engine this backend created, whose run's infrastructure is wanted
     * @return the infrastructure controls, never {@code null}
     */
    default StoreInfrastructure infrastructure(EventStorageEngine engine) {
        return StoreInfrastructure.none();
    }

    /**
     * Indicates whether this store's transaction commits somewhere other than in
     * {@code EventStorageEngine.AppendTransaction.commit()}.
     * <p>
     * <b>This is what decides whether an acknowledged append is an observable thing at all.</b> On a store whose
     * {@code commit()} does the work, that call returning is the moment of durability and the harness can record the
     * append as acknowledged on the strength of it. On a store whose transaction lives on the processing context,
     * {@code commit()} does nothing and races the database transaction rather than preceding it, so it returning says
     * nothing about whether anything was kept -- and a run that then breaks the connection produces appends the harness
     * calls acknowledged which the client's own command never saw succeed.
     * <p>
     * A durability oracle therefore refuses to decide on such a store rather than reporting the harness's accounting as
     * the store's defect. Fixing that needs the append's outcome taken from the transaction's fate instead of the
     * engine's call, which is a change to the recording path and not to any oracle.
     *
     * @return {@code true} when the transaction that makes an append durable is not the append transaction's own commit
     */
    default boolean commitsOutsideAppendTransaction() {
        return false;
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
     * Returns the version facts this backend contributes to a run's history header.
     * <p>
     * <b>A backend is not one thing, and a verdict from one is unreadable without knowing which combination produced
     * it.</b> A store reached over a wire is this reactor crossed with a client library crossed with a server version,
     * and any of the three moving changes what a run means. The block that kept Axon Server out of this suite for a whole
     * phase was exactly that: an abstract method added to a storage-engine interface which the released client library
     * had not implemented, accepted by {@code javac} and refused by the JVM. Recording the combination as data is what
     * makes "is this divergence the framework's or the skew's" a lookup instead of an argument.
     * <p>
     * A backend that adapts a released artefact to this reactor must also name what it adapted, under a key ending in
     * {@code .shimmed}, so that no verdict from it can be quoted without the adaptation.
     * <p>
     * The default is empty, which is right for a store that is this reactor and nothing else.
     *
     * @return the version facts, keyed by what they describe, for example {@code connector} and {@code image}
     */
    default java.util.Map<String, String> versions() {
        return java.util.Map.of();
    }

    /**
     * Returns the version of the framework the run is driving.
     * <p>
     * Read from the {@code hunt.frameworkVersion} property the module's surefire configuration fills from the reactor's
     * own version, falling back to the manifest of the jar the storage-engine interface was loaded from -- which is
     * present when the module resolves the framework from the local repository and absent when it resolves it from
     * another module's {@code target/classes}.
     *
     * @return the framework version, or {@code unknown} when neither source has one
     */
    static String frameworkVersion() {
        String declared = System.getProperty("hunt.frameworkVersion");
        if (declared != null && !declared.isBlank() && !declared.startsWith("$")) {
            return declared;
        }
        String fromManifest = EventStorageEngine.class.getPackage().getImplementationVersion();
        return fromManifest == null || fromManifest.isBlank() ? "unknown" : fromManifest;
    }

    /**
     * Returns the version of the jar the given class was loaded from, for a backend naming its client library.
     *
     * @param type a class of the artefact whose version is wanted
     * @return the artefact's implementation version, or {@code unknown} when its manifest carries none
     */
    static String versionOf(Class<?> type) {
        String version = type.getPackage().getImplementationVersion();
        return version == null || version.isBlank() ? "unknown" : version;
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
