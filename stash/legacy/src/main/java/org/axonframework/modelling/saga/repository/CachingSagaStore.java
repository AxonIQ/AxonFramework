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

import org.axonframework.common.AxonConfigurationException;
import org.axonframework.common.caching.Cache;
import org.axonframework.modelling.saga.AssociationValue;
import org.axonframework.modelling.saga.AssociationValues;
import org.axonframework.modelling.saga.SagaRepository;

import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;

import static org.axonframework.common.BuilderUtils.assertNonNull;

/**
 * Saga Repository implementation that adds caching behavior to the repository it wraps. Both associations and sagas are
 * cached, making loading them faster. Commits and adds are always delegated to the wrapped repository. Loads are only
 * delegated if the cache does not contain the necessary entries.
 * <p>
 * Updating associations involves a read and write, which are performed atomically. Therefore, it is unsafe to add or
 * remove specific associations outside this instance. Obviously, clearing and evictions are safe.
 *
 * @param <T> The saga type
 * @author Allard Buijze
 * @since 2.0
 */
public class CachingSagaStore<T> implements SagaStore<T> {

    private final SagaStore<T> delegate;
    private final Cache associationsCache;
    private final Cache sagaCache;

    /**
     * Instantiate a {@link CachingSagaStore} based on the fields contained in the {@link Builder}.
     * <p>
     * Will assert that the {@code delegate} {@link SagaStore}, Saga {@code associationsCache} and {@code sagaCache} are
     * not {@code null}, and will throw an {@link AxonConfigurationException} if any of them is {@code null}.
     *
     * @param builder the {@link Builder} used to instantiate a {@link CachingSagaStore} instance
     */
    protected CachingSagaStore(Builder<T> builder) {
        builder.validate();
        this.delegate = builder.delegateSagaStore;
        this.associationsCache = builder.associationsCache;
        this.sagaCache = builder.sagaCache;
    }

    /**
     * Instantiate a Builder to be able to create a {@link CachingSagaStore}.
     * <p>
     * The {@code delegateSagaStore} of type {@link SagaStore}, the {@code associationsCache} and {@code sagaCache}
     * (both of type {@link Cache}) are <b>hard requirements</b> and as such should be provided.
     *
     * @param <T> a generic specifying the Saga type contained in this {@link SagaRepository } implementation
     * @return a Builder to be able to create a {@link CachingSagaStore}
     */
    public static <T> Builder<T> builder() {
        return new Builder<>();
    }

    @Override
    public Set<String> findSagas(Class<? extends T> sagaType, AssociationValue associationValue) {
        final String key = cacheKey(associationValue, sagaType);
        return associationsCache.computeIfAbsent(
                key,
                () -> {
                    // We copy the original collection in a thread-safe implementation, since the original might be
                    // changed while the SagaManager is reading it using the insertSaga/deleteSaga methods.
                    return new ConcurrentSkipListSet<>(delegate.findSagas(sagaType, associationValue));
                }
        );
    }

    @Override
    public <S extends T> Entry<S> loadSaga(Class<S> sagaType, String sagaIdentifier) {
        Entry<S> saga = sagaCache.get(sagaIdentifier);
        if (saga == null) {
            saga = delegate.loadSaga(sagaType, sagaIdentifier);
            if (saga != null) {
                sagaCache.put(sagaIdentifier, new CacheEntry<T>(saga));
            }
        }
        return saga;
    }

    @Override
    public void insertSaga(Class<? extends T> sagaType, String sagaIdentifier, T saga,
                           Set<AssociationValue> associationValues) {
        delegate.insertSaga(sagaType, sagaIdentifier, saga, associationValues);
        sagaCache.put(sagaIdentifier, new CacheEntry<>(saga, associationValues));
        addCachedAssociations(associationValues, sagaIdentifier, sagaType);
    }

    @Override
    public void deleteSaga(Class<? extends T> sagaType, String sagaIdentifier,
                           Set<AssociationValue> associationValues) {
        sagaCache.remove(sagaIdentifier);
        associationValues.forEach(av -> removeAssociationValueFromCache(sagaType, sagaIdentifier, av));
        delegate.deleteSaga(sagaType, sagaIdentifier, associationValues);
    }

    private void removeAssociationValueFromCache(Class<?> sagaType,
                                                 String sagaIdentifier,
                                                 AssociationValue associationValue) {
        String key = cacheKey(associationValue, sagaType);
        associationsCache.computeIfPresent(key, associations -> {
            //noinspection unchecked
            ((Set<String>) associations).remove(sagaIdentifier);
            //noinspection unchecked
            return ((Set<String>) associations).isEmpty() ? null : associations;
        });
    }

    /**
     * Registers the associations of a saga with given {@code sagaIdentifier} and given {@code sagaType} with the
     * associations cache.
     *
     * @param associationValues the association values of the saga
     * @param sagaIdentifier    the identifier of the saga
     * @param sagaType          the type of the saga
     */
    protected void addCachedAssociations(Iterable<AssociationValue> associationValues,
                                         String sagaIdentifier,
                                         Class<?> sagaType) {
        for (AssociationValue associationValue : associationValues) {
            String key = cacheKey(associationValue, sagaType);
            associationsCache.computeIfPresent(key, identifiers -> {
                //noinspection unchecked
                ((Set<String>) identifiers).add(sagaIdentifier);
                return identifiers;
            });
        }
    }

    @Override
    public void updateSaga(Class<? extends T> sagaType,
                           String sagaIdentifier,
                           T saga,
                           AssociationValues associationValues) {
        sagaCache.put(sagaIdentifier, new CacheEntry<>(saga, associationValues.asSet()));
        delegate.updateSaga(sagaType, sagaIdentifier, saga, associationValues);
        associationValues.removedAssociations()
                         .forEach(av -> removeAssociationValueFromCache(sagaType, sagaIdentifier, av));
        addCachedAssociations(associationValues.addedAssociations(), sagaIdentifier, sagaType);
    }

    private String cacheKey(AssociationValue associationValue, Class<?> sagaType) {
        return sagaType.getName() + "/" + associationValue.getKey() + "=" + associationValue.getValue();
    }

    /**
     * Builder class to instantiate a {@link CachingSagaStore}.
     * <p>
     * The {@code delegateSagaStore} of type {@link SagaStore}, the {@code associationsCache} and {@code sagaCache}
     * (both of type {@link Cache}) are <b>hard requirements</b> and as such should be provided.
     *
     * @param <T> a generic specifying the Saga type contained in this {@link SagaRepository} implementation
     */
    public static class Builder<T> {

        private SagaStore<T> delegateSagaStore;
        private Cache associationsCache;
        private Cache sagaCache;

        /**
         * Sets the {@link SagaStore} instance providing access to (persisted) entries.
         *
         * @param delegateSagaStore the {@link SagaStore} instance providing access to (persisted) entries
         * @return the current Builder instance, for fluent interfacing
         */
        public Builder<T> delegateSagaStore(SagaStore<T> delegateSagaStore) {
            assertNonNull(delegateSagaStore, "Delegate SagaStore may not be null");
            this.delegateSagaStore = delegateSagaStore;
            return this;
        }

        /**
         * Sets the {@code associationsCache} of type {@link Cache} used to store Saga associations with.
         *
         * @param associationsCache a {@link Cache} used to store Saga associations with
         * @return the current Builder instance, for fluent interfacing
         */
        public Builder<T> associationsCache(Cache associationsCache) {
            assertNonNull(associationsCache, "AssociationsCache may not be null");
            this.associationsCache = associationsCache;
            return this;
        }

        /**
         * Sets the {@code sagaCache} of type {@link Cache} used to store Sagas with.
         *
         * @param sagaCache a {@link Cache} used to store Sagas with
         * @return the current Builder instance, for fluent interfacing
         */
        public Builder<T> sagaCache(Cache sagaCache) {
            assertNonNull(sagaCache, "SagaCache may not be null");
            this.sagaCache = sagaCache;
            return this;
        }

        /**
         * Initializes a {@link CachingSagaStore} as specified through this Builder.
         *
         * @return a {@link CachingSagaStore} as specified through this Builder
         */
        public CachingSagaStore<T> build() {
            return new CachingSagaStore<>(this);
        }

        /**
         * Validates whether the fields contained in this Builder are set accordingly.
         *
         * @throws AxonConfigurationException if one field is asserted to be incorrect according to the Builder's
         *                                    specifications
         */
        protected void validate() throws AxonConfigurationException {
            assertNonNull(delegateSagaStore, "The delegate SagaStore is a hard requirement and should be provided");
            assertNonNull(associationsCache, "The associationsCache is a hard requirement and should be provided");
            assertNonNull(sagaCache, "The sagaCache is a hard requirement and should be provided");
        }
    }

    private static class CacheEntry<T> implements Entry<T> {

        private final T saga;
        private final Set<AssociationValue> associationValues;

        public CacheEntry(T saga, Set<AssociationValue> associationValues) {
            this.saga = saga;
            this.associationValues = associationValues;
        }

        public <S extends T> CacheEntry(Entry<S> other) {
            this.saga = other.saga();
            this.associationValues = other.associationValues();
        }

        @Override
        public Set<AssociationValue> associationValues() {
            return associationValues;
        }

        @Override
        public T saga() {
            return saga;
        }
    }
}
