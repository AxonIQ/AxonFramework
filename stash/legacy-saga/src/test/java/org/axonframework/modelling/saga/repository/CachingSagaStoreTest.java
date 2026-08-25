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

import org.axonframework.common.FutureUtils;
import org.axonframework.common.IdentifierFactory;
import org.axonframework.common.caching.Cache;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.unitofwork.LegacyDefaultUnitOfWork;
import org.axonframework.messaging.unitofwork.LegacyUnitOfWork;
import org.axonframework.modelling.saga.AssociationValue;
import org.axonframework.modelling.saga.AssociationValuesImpl;
import org.axonframework.modelling.saga.Saga;
import org.axonframework.modelling.saga.SagaRepository;
import org.axonframework.modelling.saga.repository.inmemory.InMemorySagaStore;
import org.junit.jupiter.api.*;
import org.mockito.*;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import static java.util.Collections.singleton;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Abstract test class for validating the {@link CachingSagaStore}. Expects implementations to construct the type of
 * {@link Cache} used during testing.
 *
 * @author Allard Buijze
 */
public abstract class CachingSagaStoreTest {

    private SagaStore<StubSaga> delegate;
    private Cache sagaCache;
    private Cache associationsCache;

    private CachingSagaStore<StubSaga> testSubject;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        //noinspection rawtypes
        delegate = spy((SagaStore) new InMemorySagaStore());
        sagaCache = spy(sagaCache());
        associationsCache = spy(associationCache());

        testSubject = CachingSagaStore.<StubSaga>builder()
                                      .delegateSagaStore(delegate)
                                      .sagaCache(sagaCache)
                                      .associationsCache(associationsCache)
                                      .build();
    }

    /**
     * Retrieve the saga {@link Cache} used for testing.
     *
     * @return The saga {@link Cache} used for testing.
     */
    abstract Cache sagaCache();

    /**
     * Retrieve the association value entry {@link Cache} used for testing.
     *
     * @return The association value entry {@link Cache} used for testing.
     */
    abstract Cache associationCache();

    private void clearCaches() {
        sagaCache.removeAll();
        associationsCache.removeAll();
    }

    @Test
    void sagaAddedToCacheOnAdd() {
        testSubject.insertSaga(StubSaga.class, "123", new StubSaga(), singleton(new AssociationValue("key", "value")));

        verify(sagaCache).put(eq("123"), any());
        verify(associationsCache, never()).put(any(), any());
    }

    @Test
    void associationsAddedToCacheOnLoad() {
        testSubject.insertSaga(StubSaga.class, "id", new StubSaga(), singleton(new AssociationValue("key", "value")));

        verify(associationsCache, never()).put(any(), any());

        clearCaches();
        reset(sagaCache, associationsCache);

        final AssociationValue associationValue = new AssociationValue("key", "value");

        Set<String> actual = testSubject.findSagas(StubSaga.class, associationValue);
        assertEquals(singleton("id"), actual);
        //noinspection unchecked
        ArgumentCaptor<Supplier<?>> captor = ArgumentCaptor.forClass(Supplier.class);
        verify(associationsCache, atLeast(1)).computeIfAbsent(
                eq("org.axonframework.modelling.saga.repository.StubSaga/key=value"),
                captor.capture()
        );
        assertEquals(Collections.singleton("id"), captor.getValue().get());
    }

    @Test
    void sagaAddedToCacheOnLoad() {
        StubSaga saga = new StubSaga();
        testSubject.insertSaga(StubSaga.class, "id", saga, singleton(new AssociationValue("key", "value")));

        clearCaches();
        reset(sagaCache, associationsCache);

        SagaStore.Entry<StubSaga> actual = testSubject.loadSaga(StubSaga.class, "id");
        assertSame(saga, actual.saga());

        verify(sagaCache).get("id");
        verify(sagaCache).put(eq("id"), any());
        verify(associationsCache, never()).put(any(), any());
    }

    @Test
    void sagaNotAddedToCacheWhenLoadReturnsNull() {
        clearCaches();
        reset(sagaCache, associationsCache);

        SagaStore.Entry<StubSaga> actual = testSubject.loadSaga(StubSaga.class, "id");
        assertNull(actual);

        verify(sagaCache).get("id");
        verify(sagaCache, never()).put(eq("id"), any());
        verify(associationsCache, never()).put(any(), any());
    }


    @Test
    void commitDelegatedAfterAddingToCache() {
        StubSaga saga = new StubSaga();
        AssociationValue associationValue = new AssociationValue("key", "value");
        testSubject.insertSaga(StubSaga.class, "123", saga, singleton(associationValue));

        verify(associationsCache, never()).put(any(), any());
        verify(delegate).insertSaga(StubSaga.class, "123", saga, singleton(associationValue));
    }

    @Test
    void sagaAndAssociationsRemovedFromCacheOnDelete() {
        String testSagaId = "123";
        AssociationValue testAssociationValue = new AssociationValue("key", "value");
        AssociationValuesImpl testUpdatedAssociations = new AssociationValuesImpl();
        testUpdatedAssociations.add(testAssociationValue);
        String expectedAssociationKey = "org.axonframework.modelling.saga.repository.StubSaga/key=value";

        // Insert a Saga into the store, thus adding it to the cache.
        testSubject.insertSaga(StubSaga.class, testSagaId, new StubSaga(), singleton(testAssociationValue));
        assertTrue(sagaCache.containsKey(testSagaId));

        // Find the Saga, as this will set the association values in the cache.
        // Insert only adds association values to the cache, if they were already present.
        testSubject.findSagas(StubSaga.class, testAssociationValue);
        assertTrue(sagaCache.containsKey(testSagaId));
        assertTrue(associationsCache.containsKey(expectedAssociationKey));

        // Update the Saga instance, to ensure updating the Saga and adding "new" associations to the cache works.
        testSubject.updateSaga(StubSaga.class, testSagaId, new StubSaga(), testUpdatedAssociations);
        assertTrue(sagaCache.containsKey(testSagaId));
        assertTrue(associationsCache.containsKey(expectedAssociationKey));

        // Delete the Saga, to ensure it's removed from the cache.
        testSubject.deleteSaga(StubSaga.class, testSagaId, singleton(testAssociationValue));
        assertFalse(sagaCache.containsKey(testSagaId));
        assertFalse(associationsCache.containsKey(expectedAssociationKey));
    }

    @Test
    void canHandleConcurrentReadsAndWrites() {
        int concurrentOperations = 32;

        AssociationValue associationValue = new AssociationValue("StubSaga-id", "value");
        Set<AssociationValue> associationValues = singleton(associationValue);
        ExecutorService executor = Executors.newFixedThreadPool(16);

        try {
            IntStream.range(0, concurrentOperations)
                     .mapToObj(i -> CompletableFuture.runAsync(
                             () -> {
                                 try {
                                     String sagaId = IdentifierFactory.getInstance().generateIdentifier();

                                     testSubject.insertSaga(
                                             StubSaga.class, sagaId, mock(StubSaga.class), associationValues
                                     );
                                     testSubject.findSagas(StubSaga.class, associationValue);
                                     testSubject.deleteSaga(
                                             StubSaga.class, sagaId, associationValues
                                     );
                                 } catch (Exception e) {
                                     throw new RuntimeException(e);
                                 }
                             },
                             executor
                     ))
                     .reduce(CompletableFuture::allOf)
                     .orElse(FutureUtils.emptyCompletedFuture())
                     .get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            fail("An unexpected exception occurred during concurrent invocations on the CachingSagaStore.", e);
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void canHandleConcurrentReadsAndWritesThroughAnnotatedSagaRepository() {
        SagaRepository<StubSaga> sagaRepository = AnnotatedSagaRepository.<StubSaga>builder()
                                                                         .sagaType(StubSaga.class)
                                                                         .sagaStore(testSubject)
                                                                         .build();
        int concurrentOperations = 32;
        ExecutorService executor = Executors.newFixedThreadPool(16);
        AssociationValue associationValue = new AssociationValue("StubSaga-id", "value");

        try {
            IntStream.range(0, concurrentOperations)
                     .mapToObj(i -> CompletableFuture.runAsync(
                             () -> {
                                 try {
                                     LegacyUnitOfWork<Message> uow = LegacyDefaultUnitOfWork.startAndGet(null);
                                     String sagaId = IdentifierFactory.getInstance().generateIdentifier();
                                     // Create instances
                                     Saga<StubSaga> saga = sagaRepository.createInstance(sagaId, StubSaga::new);
                                     uow.execute((ctx) -> saga.getAssociationValues().add(associationValue));
                                     // Find Saga identifiers
                                     Set<String> sagaIds = sagaRepository.find(associationValue);
                                     // Load Sagas
                                     LegacyDefaultUnitOfWork.startAndGet(null)
                                                            .execute((ctx) -> sagaIds.forEach(sagaRepository::load));
                                 } catch (Exception e) {
                                     throw new RuntimeException(e);
                                 }
                             },
                             executor
                     ))
                     .reduce(CompletableFuture::allOf)
                     .orElse(FutureUtils.emptyCompletedFuture())
                     .get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            fail("An unexpected exception occurred during concurrent invocations on the CachingSagaStore.", e);
        } finally {
            executor.shutdown();
        }
    }
}
