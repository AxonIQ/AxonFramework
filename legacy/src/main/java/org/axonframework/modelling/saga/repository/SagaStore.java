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

import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.modelling.saga.AssociationValue;
import org.axonframework.modelling.saga.AssociationValues;
import org.jspecify.annotations.Nullable;

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
     * @param context          the processing context, or {@code null} when no processing lifecycle is available
     * @return A set of identifiers of sagas having the correct type and association value
     */
    Set<String> findSagas(Class<? extends T> sagaType, AssociationValue associationValue,
                          @Nullable ProcessingContext context);

    /**
     * Loads a known saga {@link Entry} instance with given {@code sagaType} and unique {@code sagaIdentifier}.
     * <p>
     * Due to the concurrent nature of Sagas, it is not unlikely for a Saga to have ceased to exist after it has been
     * found based on associations. Therefore, a repository should return {@code null} in case a Saga doesn't
     * exists, as opposed to throwing an exception.
     *
     * @param sagaType       The type of the returned saga entry
     * @param sagaIdentifier The unique identifier of the returned saga entry
     * @param context        the processing context, or {@code null} when no processing lifecycle is available
     * @return The saga entry, or {@code null} if no such saga exists
     */
    @Nullable <S extends T> Entry<S> loadSaga(Class<S> sagaType, String sagaIdentifier,
                                              @Nullable ProcessingContext context);

    /**
     * Deletes a Saga with given {@code sagaType} and {@code sagaIdentifier} and all its associations. For convenience
     * all known association values are passed along as well, which has the  advantage that the saga store is not
     * required to keep an index of association value to saga identifier.
     *
     * @param sagaType          The type of saga to delete
     * @param sagaIdentifier    The identifier of the saga to delete
     * @param associationValues The known associations of the saga
     * @param context           the processing context, or {@code null} when no processing lifecycle is available
     */
    void deleteSaga(Class<? extends T> sagaType, String sagaIdentifier, Set<AssociationValue> associationValues,
                    @Nullable ProcessingContext context);

    /**
     * Adds a new Saga and its initial association values to the store.
     *
     * @param sagaType          The type of the Saga
     * @param sagaIdentifier    The identifier of the Saga
     * @param saga              The Saga instance
     * @param associationValues The initial association values of the Saga
     * @param context           the processing context, or {@code null} when no processing lifecycle is available
     */
    void insertSaga(Class<? extends T> sagaType, String sagaIdentifier, T saga, Set<AssociationValue> associationValues,
                    @Nullable ProcessingContext context);

    /**
     * Updates a given Saga after its state was modified, applying the association values added and removed since it was
     * last stored.
     *
     * @param sagaType          The type of the Saga
     * @param sagaIdentifier    The identifier of the Saga
     * @param saga              The Saga instance
     * @param associationValues The association values of the Saga, carrying the additions and removals to apply
     * @param context           the processing context, or {@code null} when no processing lifecycle is available
     */
    void updateSaga(Class<? extends T> sagaType, String sagaIdentifier, T saga, AssociationValues associationValues,
                    @Nullable ProcessingContext context);

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
