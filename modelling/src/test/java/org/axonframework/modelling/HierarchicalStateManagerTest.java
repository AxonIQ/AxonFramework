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

package org.axonframework.modelling;

import org.axonframework.common.FutureUtils;
import org.axonframework.common.infra.ComponentDescriptor;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.StubProcessingContext;
import org.axonframework.modelling.repository.ManagedEntity;
import org.axonframework.modelling.repository.Repository;
import org.junit.jupiter.api.*;
import org.mockito.*;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class HierarchicalStateManagerTest {

    @Test
    void resolvesEntityFromChildIfExistsInBoth() {
        StateManager parent = createStringSimpleStateManager("parent");
        StateManager child = createStringSimpleStateManager("child");

        HierarchicalStateManager stateManager = HierarchicalStateManager.create(parent, child);

        verifyHasAsResult(stateManager, "child");
    }

    @Test
    void resolvesEntityFromParentIfDoesNotExistInChild() {
        StateManager parent = createStringSimpleStateManager("parent");
        StateManager child = SimpleStateManager.named("child");

        HierarchicalStateManager stateManager = HierarchicalStateManager.create(parent, child);

        verifyHasAsResult(stateManager, "parent");
    }

    @Test
    void resolvesEntityFromChildIfDoesNotExistInParent() {
        StateManager parent = SimpleStateManager.named("parent");
        StateManager child = createStringSimpleStateManager("child");

        HierarchicalStateManager stateManager = HierarchicalStateManager.create(parent, child);

        verifyHasAsResult(stateManager, "child");
    }

    @Test
    void resolvesPolymorphicEntityFromChildIfRepositoryRegisteredForSupertype() {
        StateManager parent = SimpleStateManager.named("parent");
        StateManager child = SimpleStateManager.named("child")
                                               .register(String.class,
                                                         TestEntity.class,
                                                         (id, ctx) -> CompletableFuture.completedFuture(
                                                                 new TestSubEntity("child")),
                                                         (id, state, ctx) -> FutureUtils.emptyCompletedFuture());

        HierarchicalStateManager stateManager = HierarchicalStateManager.create(parent, child);

        TestSubEntity entity = stateManager.loadEntity(TestSubEntity.class, "id", new StubProcessingContext())
                                           .join();

        assertNotNull(entity);
        assertEquals("child", entity.value());
    }

    @Test
    void resolvesPolymorphicEntityFromParentIfRepositoryRegisteredForSupertypeAndNotInChild() {
        StateManager parent = SimpleStateManager.named("parent")
                                                .register(String.class,
                                                          TestEntity.class,
                                                          (id, ctx) -> CompletableFuture.completedFuture(
                                                                  new TestSubEntity("parent")),
                                                          (id, state, ctx) -> FutureUtils.emptyCompletedFuture());
        StateManager child = SimpleStateManager.named("child");

        HierarchicalStateManager stateManager = HierarchicalStateManager.create(parent, child);

        TestSubEntity entity = stateManager.loadEntity(TestSubEntity.class, "id", new StubProcessingContext())
                                           .join();

        assertNotNull(entity);
        assertEquals("parent", entity.value());
    }

    @Test
    void resolvesPolymorphicEntityFromChildWhenBothChildAndParentHaveMatchingSupertype() {
        StateManager parent = SimpleStateManager.named("parent")
                                                .register(String.class,
                                                          TestEntity.class,
                                                          (id, ctx) -> CompletableFuture.completedFuture(
                                                                  new TestSubEntity("parent")),
                                                          (id, state, ctx) -> FutureUtils.emptyCompletedFuture());
        StateManager child = SimpleStateManager.named("child")
                                               .register(String.class,
                                                         TestEntity.class,
                                                         (id, ctx) -> CompletableFuture.completedFuture(
                                                                 new TestSubEntity("child")),
                                                         (id, state, ctx) -> FutureUtils.emptyCompletedFuture());

        HierarchicalStateManager stateManager = HierarchicalStateManager.create(parent, child);

        ManagedEntity<String, TestSubEntity> managedEntity =
                stateManager.loadManagedEntity(TestSubEntity.class, "id", new StubProcessingContext()).join();

        assertNotNull(managedEntity.entity());
        assertEquals("child", managedEntity.entity().value());
    }

    @Test
    void nonMissingRepositoryExceptionFromChildPropagatesUnchangedWithoutParentFallback() {
        RuntimeException childFailure = new RuntimeException("child loader failure");
        StateManager parent = createStringSimpleStateManager("parent");
        StateManager child = SimpleStateManager.named("child")
                                               .register(String.class,
                                                         String.class,
                                                         (id, ctx) -> CompletableFuture.failedFuture(childFailure),
                                                         (id, state, ctx) -> FutureUtils.emptyCompletedFuture());

        HierarchicalStateManager stateManager = HierarchicalStateManager.create(parent, child);

        CompletionException exception = assertThrows(CompletionException.class, () -> {
            stateManager.loadEntity(String.class, "id", new StubProcessingContext())
                        .join();
        });
        assertSame(childFailure, exception.getCause());
    }

    @Test
    void synchronousMissingRepositoryExceptionThrowFromChildStillTriggersParentFallback() {
        StateManager parent = createStringSimpleStateManager("parent");
        StateManager child = new SynchronouslyThrowingStateManager();

        HierarchicalStateManager stateManager = HierarchicalStateManager.create(parent, child);

        verifyHasAsResult(stateManager, "parent");
    }

    @Test
    void resolvesEntityThroughNestedHierarchicalStateManagerComposition() {
        StateManager grandparent = createStringSimpleStateManager("grandparent");
        StateManager parent = HierarchicalStateManager.create(grandparent, SimpleStateManager.named("intermediate"));
        StateManager child = SimpleStateManager.named("child");

        HierarchicalStateManager stateManager = HierarchicalStateManager.create(parent, child);

        verifyHasAsResult(stateManager, "grandparent");
    }

    @Test
    void completesExceptionallyIfExistsInNeither() {
        StateManager parent = SimpleStateManager.named("parent");
        StateManager child = SimpleStateManager.named("child");

        HierarchicalStateManager stateManager = HierarchicalStateManager.create(parent, child);

        CompletionException exception = Assertions.assertThrows(CompletionException.class, () -> {
            stateManager.loadEntity(String.class, "id", new StubProcessingContext())
                        .join();
        });
        assertInstanceOf(MissingRepositoryException.class, exception.getCause());
    }

    @Test
    void combinesTypesOfBothChildAndParentInRepositoriesMethods() {
        StateManager parent = SimpleStateManager.named("parent")
                                                .register(createMockForTypes(String.class, Integer.class))
                                                .register(createMockForTypes(Integer.class, Integer.class));
        StateManager child = SimpleStateManager.named("child")
                                               .register(createMockForTypes(Boolean.class, Integer.class))
                                               .register(createMockForTypes(String.class, Boolean.class));

        HierarchicalStateManager stateManager = HierarchicalStateManager.create(parent, child);

        Set<Class<?>> classes = stateManager.registeredEntities();
        assertEquals(3, classes.size());
        Assertions.assertTrue(classes.contains(String.class));
        Assertions.assertTrue(classes.contains(Integer.class));
        Assertions.assertTrue(classes.contains(Boolean.class));

        Set<Class<?>> stringIds = stateManager.registeredIdsFor(String.class);
        assertEquals(2, stringIds.size());
        Assertions.assertTrue(stringIds.contains(Integer.class));
        Assertions.assertTrue(stringIds.contains(Boolean.class));

        Set<Class<?>> integerIds = stateManager.registeredIdsFor(Integer.class);
        assertEquals(1, integerIds.size());
        Assertions.assertTrue(integerIds.contains(Integer.class));

        Set<Class<?>> booleanIds = stateManager.registeredIdsFor(Boolean.class);
        assertEquals(1, booleanIds.size());
        Assertions.assertTrue(booleanIds.contains(Integer.class));
    }

    @SuppressWarnings("rawtypes")
    private Repository<?, ?> createMockForTypes(Class<?> entityType, Class<?> idType) {
        Repository mock = Mockito.mock(Repository.LifecycleManagement.class);
        Mockito.when(mock.idType()).thenReturn(idType);
        Mockito.when(mock.entityType()).thenReturn(entityType);
        return mock;
    }

    private static void verifyHasAsResult(HierarchicalStateManager stateManager, String child) {
        stateManager.loadEntity(String.class, "id", new StubProcessingContext())
                    .thenAccept(entity -> {
                        assertEquals(child, entity);
                    })
                    .join();
    }

    private static StateManager createStringSimpleStateManager(String value) {
        return SimpleStateManager.named(value).register(
                String.class, String.class,
                (id, ctx) -> CompletableFuture.completedFuture(value),
                (id, state, ctx) -> FutureUtils.emptyCompletedFuture()
        );
    }

    private interface TestEntity {

    }

    private record TestSubEntity(String value) implements TestEntity {

    }

    /**
     * {@link StateManager} stub that throws {@link MissingRepositoryException} synchronously rather than completing
     * a {@link CompletableFuture} exceptionally, exercising the {@code FutureUtils#runFailing} wrapping in
     * {@link HierarchicalStateManager#loadManagedEntity(Class, Object, ProcessingContext)}.
     */
    private static final class SynchronouslyThrowingStateManager implements StateManager {

        @Override
        public <ID, T> StateManager register(Repository<ID, T> repository) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <ID, T> CompletableFuture<ManagedEntity<ID, T>> loadManagedEntity(Class<T> type,
                                                                                  ID id,
                                                                                  ProcessingContext context) {
            throw new MissingRepositoryException(id.getClass(), type);
        }

        @Override
        public Set<Class<?>> registeredEntities() {
            return Set.of();
        }

        @Override
        public Set<Class<?>> registeredIdsFor(Class<?> entityType) {
            return Set.of();
        }

        @Override
        public <ID, T> Repository<ID, T> repository(Class<T> entityType, Class<ID> idType) {
            return null;
        }

        @Override
        public void describeTo(ComponentDescriptor descriptor) {
            // No-op - not required for testing
        }
    }
}