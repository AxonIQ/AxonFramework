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
