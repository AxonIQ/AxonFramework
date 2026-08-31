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
import org.axonframework.messaging.core.EmptyApplicationContext;
import org.axonframework.messaging.core.unitofwork.SimpleUnitOfWorkFactory;
import org.axonframework.messaging.core.unitofwork.UnitOfWork;
import org.axonframework.modelling.saga.AssociationValue;
import org.axonframework.modelling.saga.AssociationValuesImpl;
import org.axonframework.modelling.saga.repository.inmemory.InMemorySagaStore;
import org.junit.jupiter.api.*;
import org.mockito.*;

import java.util.Collections;
import java.util.HashSet;
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
        testSubject.insertSaga(
                StubSaga.class, "123", new StubSaga(), singleton(new AssociationValue("key", "value")), null
        );

        verify(sagaCache).put(eq("123"), any());
        verify(associationsCache, never()).put(any(), any());
    }

    @Test
    void associationsAddedToCacheOnLoad() {
        testSubject.insertSaga(
                StubSaga.class, "id", new StubSaga(), singleton(new AssociationValue("key", "value")), null
        );

        verify(associationsCache, never()).put(any(), any());

        clearCaches();
        reset(sagaCache, associationsCache);

        final AssociationValue associationValue = new AssociationValue("key", "value");

        Set<String> actual = testSubject.findSagas(StubSaga.class, associationValue, null);
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
        testSubject.insertSaga(StubSaga.class, "id", saga, singleton(new AssociationValue("key", "value")), null);

        clearCaches();
        reset(sagaCache, associationsCache);

        SagaStore.Entry<StubSaga> actual = testSubject.loadSaga(StubSaga.class, "id", null);
        assertSame(saga, actual.saga());

        verify(sagaCache).get("id");
        verify(sagaCache).put(eq("id"), any());
        verify(associationsCache, never()).put(any(), any());
    }

    @Test
    void sagaNotAddedToCacheWhenLoadReturnsNull() {
        clearCaches();
        reset(sagaCache, associationsCache);

        SagaStore.Entry<StubSaga> actual = testSubject.loadSaga(StubSaga.class, "id", null);
        assertNull(actual);

        verify(sagaCache).get("id");
        verify(sagaCache, never()).put(eq("id"), any());
        verify(associationsCache, never()).put(any(), any());
    }


    @Test
    void commitDelegatedAfterAddingToCache() {
        StubSaga saga = new StubSaga();
        AssociationValue associationValue = new AssociationValue("key", "value");
        testSubject.insertSaga(StubSaga.class, "123", saga, singleton(associationValue), null);

        verify(associationsCache, never()).put(any(), any());
        verify(delegate).insertSaga(StubSaga.class, "123", saga, singleton(associationValue), null);
    }

    @Test
    void insertWithContextChangesCacheOnlyAfterCommitAndUsesAssociationSnapshot() {
        AssociationValue associationValue = new AssociationValue("key", "value");
        Set<AssociationValue> associationValues = new HashSet<>(singleton(associationValue));
        testSubject.findSagas(StubSaga.class, associationValue, null);

        UnitOfWork unitOfWork = new SimpleUnitOfWorkFactory(EmptyApplicationContext.INSTANCE).create();
        unitOfWork.runOnInvocation(context -> {
            testSubject.insertSaga(StubSaga.class, "123", new StubSaga(), associationValues, context);
            associationValues.clear();

            assertFalse(sagaCache.containsKey("123"));
            assertTrue(testSubject.findSagas(StubSaga.class, associationValue, context).isEmpty());
        });

        FutureUtils.joinAndUnwrap(unitOfWork.execute());

        assertTrue(sagaCache.containsKey("123"));
        assertEquals(singleton("123"), testSubject.findSagas(StubSaga.class, associationValue, null));
        assertEquals(singleton(associationValue),
                     testSubject.loadSaga(StubSaga.class, "123", null).associationValues());
    }

    @Test
    void insertWithContextDoesNotChangeCacheAfterRollback() {
        AssociationValue associationValue = new AssociationValue("key", "value");
        testSubject.findSagas(StubSaga.class, associationValue, null);

        UnitOfWork unitOfWork = new SimpleUnitOfWorkFactory(EmptyApplicationContext.INSTANCE).create();
        unitOfWork.runOnInvocation(context -> testSubject.insertSaga(
                StubSaga.class, "123", new StubSaga(), singleton(associationValue), context
        ));
        unitOfWork.onPrepareCommit(context -> CompletableFuture.failedFuture(new IllegalStateException("rollback")));

        assertThrows(IllegalStateException.class, () -> FutureUtils.joinAndUnwrap(unitOfWork.execute()));

        assertFalse(sagaCache.containsKey("123"));
        assertTrue(testSubject.findSagas(StubSaga.class, associationValue, null).isEmpty());
    }

    @Test
    void updateWithContextChangesCacheOnlyAfterCommitAndUsesAssociationSnapshots() {
        AssociationValue oldAssociation = new AssociationValue("key", "old");
        AssociationValue newAssociation = new AssociationValue("key", "new");
        StubSaga originalSaga = new StubSaga();
        StubSaga updatedSaga = new StubSaga();
        testSubject.insertSaga(StubSaga.class, "123", originalSaga, singleton(oldAssociation), null);
        testSubject.findSagas(StubSaga.class, oldAssociation, null);
        testSubject.findSagas(StubSaga.class, newAssociation, null);

        AssociationValuesImpl associationValues = new AssociationValuesImpl(singleton(oldAssociation));
        associationValues.remove(oldAssociation);
        associationValues.add(newAssociation);
        UnitOfWork unitOfWork = new SimpleUnitOfWorkFactory(EmptyApplicationContext.INSTANCE).create();
        unitOfWork.runOnInvocation(context -> {
            testSubject.updateSaga(StubSaga.class, "123", updatedSaga, associationValues, context);
            associationValues.commit();
            associationValues.remove(newAssociation);

            assertSame(originalSaga, testSubject.loadSaga(StubSaga.class, "123", context).saga());
            assertEquals(singleton("123"), testSubject.findSagas(StubSaga.class, oldAssociation, context));
            assertTrue(testSubject.findSagas(StubSaga.class, newAssociation, context).isEmpty());
        });

        FutureUtils.joinAndUnwrap(unitOfWork.execute());

        SagaStore.Entry<StubSaga> cachedSaga = testSubject.loadSaga(StubSaga.class, "123", null);
        assertSame(updatedSaga, cachedSaga.saga());
        assertEquals(singleton(newAssociation), cachedSaga.associationValues());
        assertTrue(testSubject.findSagas(StubSaga.class, oldAssociation, null).isEmpty());
        assertEquals(singleton("123"), testSubject.findSagas(StubSaga.class, newAssociation, null));
    }

    @Test
    void updateWithContextDoesNotChangeCacheAfterRollback() {
        AssociationValue oldAssociation = new AssociationValue("key", "old");
        AssociationValue newAssociation = new AssociationValue("key", "new");
        StubSaga originalSaga = new StubSaga();
        testSubject.insertSaga(StubSaga.class, "123", originalSaga, singleton(oldAssociation), null);
        testSubject.findSagas(StubSaga.class, oldAssociation, null);
        testSubject.findSagas(StubSaga.class, newAssociation, null);

        AssociationValuesImpl associationValues = new AssociationValuesImpl(singleton(oldAssociation));
        associationValues.remove(oldAssociation);
        associationValues.add(newAssociation);
        UnitOfWork unitOfWork = new SimpleUnitOfWorkFactory(EmptyApplicationContext.INSTANCE).create();
        unitOfWork.runOnInvocation(context -> testSubject.updateSaga(
                StubSaga.class, "123", new StubSaga(), associationValues, context
        ));
        unitOfWork.onPrepareCommit(context -> CompletableFuture.failedFuture(new IllegalStateException("rollback")));

        assertThrows(IllegalStateException.class, () -> FutureUtils.joinAndUnwrap(unitOfWork.execute()));

        assertSame(originalSaga, testSubject.loadSaga(StubSaga.class, "123", null).saga());
        assertEquals(singleton("123"), testSubject.findSagas(StubSaga.class, oldAssociation, null));
        assertTrue(testSubject.findSagas(StubSaga.class, newAssociation, null).isEmpty());
    }

    @Test
    void deleteWithContextChangesCacheOnlyAfterCommit() {
        AssociationValue associationValue = new AssociationValue("key", "value");
        testSubject.insertSaga(StubSaga.class, "123", new StubSaga(), singleton(associationValue), null);
        testSubject.findSagas(StubSaga.class, associationValue, null);

        UnitOfWork unitOfWork = new SimpleUnitOfWorkFactory(EmptyApplicationContext.INSTANCE).create();
        unitOfWork.runOnInvocation(context -> {
            testSubject.deleteSaga(StubSaga.class, "123", singleton(associationValue), context);

            assertTrue(sagaCache.containsKey("123"));
            assertEquals(singleton("123"), testSubject.findSagas(StubSaga.class, associationValue, context));
        });

        FutureUtils.joinAndUnwrap(unitOfWork.execute());

        assertFalse(sagaCache.containsKey("123"));
        assertTrue(testSubject.findSagas(StubSaga.class, associationValue, null).isEmpty());
    }

    @Test
    void deleteWithContextDoesNotChangeCacheAfterRollback() {
        AssociationValue associationValue = new AssociationValue("key", "value");
        StubSaga saga = new StubSaga();
        testSubject.insertSaga(StubSaga.class, "123", saga, singleton(associationValue), null);
        testSubject.findSagas(StubSaga.class, associationValue, null);

        UnitOfWork unitOfWork = new SimpleUnitOfWorkFactory(EmptyApplicationContext.INSTANCE).create();
        unitOfWork.runOnInvocation(context -> testSubject.deleteSaga(
                StubSaga.class, "123", singleton(associationValue), context
        ));
        unitOfWork.onPrepareCommit(context -> CompletableFuture.failedFuture(new IllegalStateException("rollback")));

        assertThrows(IllegalStateException.class, () -> FutureUtils.joinAndUnwrap(unitOfWork.execute()));

        assertSame(saga, testSubject.loadSaga(StubSaga.class, "123", null).saga());
        assertEquals(singleton("123"), testSubject.findSagas(StubSaga.class, associationValue, null));
    }

    @Test
    void cacheMissesWithContextAreCachedAfterCommit() {
        AssociationValue associationValue = new AssociationValue("key", "value");
        delegate.insertSaga(StubSaga.class, "123", new StubSaga(), singleton(associationValue), null);

        UnitOfWork unitOfWork = new SimpleUnitOfWorkFactory(EmptyApplicationContext.INSTANCE).create();
        unitOfWork.runOnInvocation(context -> {
            assertNotNull(testSubject.loadSaga(StubSaga.class, "123", context));
            assertEquals(singleton("123"), testSubject.findSagas(StubSaga.class, associationValue, context));
            assertFalse(sagaCache.containsKey("123"));
            assertFalse(associationsCache.containsKey(
                    "org.axonframework.modelling.saga.repository.StubSaga/key=value"
            ));
        });

        FutureUtils.joinAndUnwrap(unitOfWork.execute());

        assertTrue(sagaCache.containsKey("123"));
        assertTrue(associationsCache.containsKey(
                "org.axonframework.modelling.saga.repository.StubSaga/key=value"
        ));
    }

    @Test
    void cacheMissesWithContextAreNotCachedAfterRollback() {
        AssociationValue associationValue = new AssociationValue("key", "value");
        delegate.insertSaga(StubSaga.class, "123", new StubSaga(), singleton(associationValue), null);

        UnitOfWork unitOfWork = new SimpleUnitOfWorkFactory(EmptyApplicationContext.INSTANCE).create();
        unitOfWork.runOnInvocation(context -> {
            assertNotNull(testSubject.loadSaga(StubSaga.class, "123", context));
            assertEquals(singleton("123"), testSubject.findSagas(StubSaga.class, associationValue, context));
            assertFalse(sagaCache.containsKey("123"));
            assertFalse(associationsCache.containsKey(
                    "org.axonframework.modelling.saga.repository.StubSaga/key=value"
            ));
        });
        unitOfWork.onPrepareCommit(context -> CompletableFuture.failedFuture(new IllegalStateException("rollback")));

        assertThrows(IllegalStateException.class, () -> FutureUtils.joinAndUnwrap(unitOfWork.execute()));

        assertFalse(sagaCache.containsKey("123"));
        assertFalse(associationsCache.containsKey(
                "org.axonframework.modelling.saga.repository.StubSaga/key=value"
        ));
    }

    @Test
    void sagaAndAssociationsRemovedFromCacheOnDelete() {
        String testSagaId = "123";
        AssociationValue testAssociationValue = new AssociationValue("key", "value");
        AssociationValuesImpl testUpdatedAssociations = new AssociationValuesImpl();
        testUpdatedAssociations.add(testAssociationValue);
        String expectedAssociationKey = "org.axonframework.modelling.saga.repository.StubSaga/key=value";

        // Insert a Saga into the store, thus adding it to the cache.
        testSubject.insertSaga(StubSaga.class, testSagaId, new StubSaga(), singleton(testAssociationValue), null);
        assertTrue(sagaCache.containsKey(testSagaId));

        // Find the Saga, as this will set the association values in the cache.
        // Insert only adds association values to the cache, if they were already present.
        testSubject.findSagas(StubSaga.class, testAssociationValue, null);
        assertTrue(sagaCache.containsKey(testSagaId));
        assertTrue(associationsCache.containsKey(expectedAssociationKey));

        // Update the Saga instance, to ensure updating the Saga and adding "new" associations to the cache works.
        testSubject.updateSaga(StubSaga.class, testSagaId, new StubSaga(), testUpdatedAssociations, null);
        assertTrue(sagaCache.containsKey(testSagaId));
        assertTrue(associationsCache.containsKey(expectedAssociationKey));

        // Delete the Saga, to ensure it's removed from the cache.
        testSubject.deleteSaga(StubSaga.class, testSagaId, singleton(testAssociationValue), null);
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
                                             StubSaga.class, sagaId, mock(StubSaga.class), associationValues, null
                                     );
                                     testSubject.findSagas(StubSaga.class, associationValue, null);
                                     testSubject.deleteSaga(
                                             StubSaga.class, sagaId, associationValues, null
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
}
