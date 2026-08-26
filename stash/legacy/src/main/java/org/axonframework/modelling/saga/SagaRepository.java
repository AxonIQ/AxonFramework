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

package org.axonframework.modelling.saga;

import java.util.Set;
import java.util.function.Supplier;

/**
 * Interface towards the storage mechanism of Saga instances. Saga Repositories can find sagas either through the
 * values
 * they have been associated with (see {@link AssociationValue}) or via their unique identifier.
 *
 * @author Allard Buijze
 * @since 3.0
 */
public interface SagaRepository<T> {

    /**
     * Find saga instances of the given {@code type} that have been associated with the given
     * {@code associationValue}.
     * <p/>
     *
     * @param associationValue The value that the returned Sagas must be associated with
     * @return A Set containing the found Saga instances. If none are found, an empty Set is returned. Will never return
     * {@code null}.
     */
    Set<String> find(AssociationValue associationValue);

    /**
     * Loads a known Saga instance by its unique identifier.
     * Due to the concurrent nature of Sagas, it is not unlikely for a Saga to have ceased to exist after it has been
     * found based on associations. Therefore, a repository should return {@code null} in case a Saga doesn't
     * exists, as opposed to throwing an exception.
     *
     * @param sagaIdentifier The unique identifier of the Saga to load
     * @return The Saga instance, or {@code null} if no such saga exists
     */
    Saga<T> load(String sagaIdentifier);

    /**
     * Creates a new Saga instance. The returned Saga will delegate event handling to the instance supplied by the given
     * {@code factoryMethod}.
     *
     * @param sagaIdentifier the identifier to use for the new saga instance
     * @param factoryMethod Used to create a new Saga delegate
     * @return a new Saga instance wrapping an instance of type {@code T}
     */
    Saga<T> createInstance(String sagaIdentifier, Supplier<T> factoryMethod);

}
