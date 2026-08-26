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

package org.axonframework.modelling.saga.repository.inmemory;

import org.axonframework.modelling.saga.AssociationValue;
import org.axonframework.modelling.saga.AssociationValues;
import org.axonframework.modelling.saga.repository.SagaStore;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

/**
 * SagaRepository implementation that stores all Saga instances in memory.
 *
 * @author Allard Buijze
 * @since 0.7
 */
public class InMemorySagaStore implements SagaStore<Object> {

    private final ConcurrentMap<String, ManagedSaga> managedSagas = new ConcurrentHashMap<>();

    @Override
    public Set<String> findSagas(Class<?> sagaType, AssociationValue associationValue) {
        return managedSagas.entrySet()
                .stream()
                .filter(avEntry -> sagaType.isInstance(avEntry.getValue().saga()))
                .filter(avEntry -> avEntry.getValue().associationValues().contains(associationValue))
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
    }

    @Override
    public void deleteSaga(Class<?> sagaType, String sagaIdentifier, Set<AssociationValue> associationValues) {
        managedSagas.remove(sagaIdentifier);
    }

    @SuppressWarnings("unchecked")
    @Override
    public <S> Entry<S> loadSaga(Class<S> sagaType, String sagaIdentifier) {
        return (Entry<S>) managedSagas.get(sagaIdentifier);
    }

    @Override
    public void insertSaga(Class<?> sagaType, String sagaIdentifier, Object saga, Set<AssociationValue> associationValues) {
        managedSagas.put(sagaIdentifier, new ManagedSaga(saga, associationValues));
    }

    @Override
    public void updateSaga(Class<?> sagaType, String sagaIdentifier, Object saga, AssociationValues associationValues) {
        managedSagas.put(sagaIdentifier, new ManagedSaga(saga, associationValues.asSet()));
    }

    /**
     * Returns the number of Sagas currently contained in this repository.
     *
     * @return the number of Sagas currently contained in this repository
     */
    public int size() {
        return managedSagas.size();
    }

    private static class ManagedSaga implements Entry<Object> {

        private final Object saga;
        private final Set<AssociationValue> associationValues;

        public ManagedSaga(Object saga, Set<AssociationValue> associationValues) {

            this.saga = saga;
            this.associationValues = associationValues;
        }

        @Override
        public Set<AssociationValue> associationValues() {
            return associationValues;
        }

        @Override
        public Object saga() {
            return saga;
        }
    }
}
