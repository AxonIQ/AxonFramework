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

package org.axonframework.modelling.saga.repository;

import org.axonframework.modelling.saga.AssociationValue;
import org.axonframework.modelling.saga.AssociationValues;

import java.util.Set;

/**
 * Provides a mechanism to find, load update and delete sagas of type {@code T} from an underlying storage like a
 * database.
 *
 * @param <T> The saga type
 */
public interface SagaStore<T> {

    /**
     * Returns identifiers of saga instances of the given {@code sagaType} that have been associated with the given
     * {@code associationValue}.
     *
     * @param sagaType         The type of the returned sagas
     * @param associationValue The value that the returned sagas must be associated with
     * @return A set of identifiers of sagas having the correct type and association value
     */
    Set<String> findSagas(Class<? extends T> sagaType, AssociationValue associationValue);

    /**
     * Loads a known saga {@link Entry} instance with given {@code sagaType} and unique {@code sagaIdentifier}.
     * <p>
     * Due to the concurrent nature of Sagas, it is not unlikely for a Saga to have ceased to exist after it has been
     * found based on associations. Therefore, a repository should return {@code null} in case a Saga doesn't
     * exists, as opposed to throwing an exception.
     *
     * @param sagaType       The type of the returned saga entry
     * @param sagaIdentifier The unique identifier of the returned saga entry
     * @return The saga entry, or {@code null} if no such saga exists
     */
    <S extends T> Entry<S> loadSaga(Class<S> sagaType, String sagaIdentifier);

    /**
     * Deletes a Saga with given {@code sagaType} and {@code sagaIdentifier} and all its associations. For convenience
     * all known association values are passed along as well, which has the  advantage that the saga store is not
     * required to keep an index of association value to saga identifier.
     *
     * @param sagaType          The type of saga to delete
     * @param sagaIdentifier    The identifier of the saga to delete
     * @param associationValues The known associations of the saga
     */
    void deleteSaga(Class<? extends T> sagaType, String sagaIdentifier, Set<AssociationValue> associationValues);

    /**
     * Adds a new Saga and its initial association values to the store. The tracking token of the event last handled by
     * the Saga (usually the event that started the Saga) is also passed as a parameter. Note that the given {@code
     * token} may be {@code null} if the Saga is not tracking the event store.
     *
     * @param sagaType          The type of the Saga
     * @param sagaIdentifier    The identifier of the Saga
     * @param saga              The Saga instance
     * @param associationValues The initial association values of the Saga
     */
    void insertSaga(Class<? extends T> sagaType, String sagaIdentifier, T saga, Set<AssociationValue> associationValues);

    /**
     * Updates a given Saga after its state was modified. The tracking token of the event last handled by the Saga is
     * also passed as a parameter. Note that the given {@code token} may be {@code null} if the Saga is not tracking the
     * event store.
     *
     * @param sagaType          The type of the Saga
     * @param sagaIdentifier    The identifier of the Saga
     * @param saga              The Saga instance
     * @param associationValues The initial association values of the Saga
     */
    void updateSaga(Class<? extends T> sagaType, String sagaIdentifier, T saga, AssociationValues associationValues);

    /**
     * Interface describing a Saga entry fetched from a SagaStore.
     *
     * @param <T> The type of the Saga
     */
    interface Entry<T> {

        /**
         * Returns the Set of association values of the fetched Saga entry.
         *
         * @return association values of the Saga
         */
        Set<AssociationValue> associationValues();

        /**
         * Returns the Saga instance in unserialized form.
         *
         * @return the saga instance
         */
        T saga();
    }
}
