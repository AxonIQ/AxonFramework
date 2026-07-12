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

package org.axonframework.modelling.tracing;

import org.axonframework.modelling.repository.tracing.TracingRepository;
import org.axonframework.messaging.tracing.support.TestSpanFactory;
import org.axonframework.messaging.tracing.support.TestSpanFactory.TestSpanType;
import org.axonframework.common.infra.ComponentDescriptor;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.StubProcessingContext;
import org.axonframework.modelling.StateManager;
import org.axonframework.modelling.repository.ManagedEntity;
import org.axonframework.modelling.repository.Repository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

class TracingStateManagerTest {

    private TestSpanFactory spanFactory;
    private RecordingStateManager delegate;
    private TracingStateManager testSubject;

    @BeforeEach
    void setUp() {
        spanFactory = new TestSpanFactory();
        delegate = new RecordingStateManager();
        testSubject = new TracingStateManager(delegate, spanFactory);
    }

    @Nested
    class LoadManagedEntity {

        @Test
        void opensAnInternalSpanWithEntityTypeAndIdentifier() {
            // given
            ProcessingContext context = new StubProcessingContext();

            // when
            testSubject.loadManagedEntity(Booking.class, "room-42", context).join();

            // then
            spanFactory.verifySpanCompleted("StateManager.loadManagedEntity Booking");
            spanFactory.verifySpanHasType("StateManager.loadManagedEntity Booking", TestSpanType.INTERNAL);
            spanFactory.verifySpanHasAttributeValue(
                    "StateManager.loadManagedEntity Booking", "axoniq.entity.type", "Booking");
            spanFactory.verifySpanHasAttributeValue(
                    "StateManager.loadManagedEntity Booking", "axoniq.entity.id", "room-42");
        }
    }

    @Nested
    class RegisterWrapsRepositories {

        @Test
        void registeringALifecycleRepositoryWrapsItSoItsOperationsAreTraced() {
            // given a plain (untraced) repository, e.g. the one an event-sourced entity module builds in its own
            // component registry - out of reach of the root registry's Repository decorator
            RecordingRepository repository = new RecordingRepository();

            // when
            testSubject.register(repository);

            // then the delegate received a traced wrapper, and loading through it opens the repository span
            assertThat(delegate.registered).isInstanceOf(TracingRepository.class);
            ((Repository<String, Booking>) delegate.registered).load("room-42", new StubProcessingContext()).join();
            spanFactory.verifySpanCompleted("Repository.load Booking");
        }

        @Test
        void registeringAnAlreadyTracedRepositoryDoesNotDoubleWrap() {
            // given a repository that the root registry's decorator already wrapped
            TracingRepository<String, Booking> alreadyTraced =
                    new TracingRepository<>(new RecordingRepository(), spanFactory);

            // when
            testSubject.register(alreadyTraced);

            // then it is registered as-is - no second wrapper, no double spans
            assertThat(delegate.registered).isSameAs(alreadyTraced);
        }
    }

    /**
     * Minimal {@link StateManager} stub recording the repository it received.
     */
    private static final class RecordingStateManager implements StateManager {

        private Repository<?, ?> registered;

        @Override
        public <ID, T> StateManager register(Repository<ID, T> repository) {
            this.registered = repository;
            return this;
        }

        @Override
        public <ID, T> CompletableFuture<ManagedEntity<ID, T>> loadManagedEntity(Class<T> type,
                                                                                 ID id,
                                                                                 ProcessingContext context) {
            return CompletableFuture.completedFuture(null);
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
    }

    /**
     * Minimal repository stub.
     */
    private static final class RecordingRepository implements Repository.LifecycleManagement<String, Booking> {

        @Override
        public Class<Booking> entityType() {
            return Booking.class;
        }

        @Override
        public Class<String> idType() {
            return String.class;
        }

        @Override
        public CompletableFuture<ManagedEntity<String, Booking>> load(String identifier,
                                                                      ProcessingContext processingContext) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<ManagedEntity<String, Booking>> loadOrCreate(String identifier,
                                                                              ProcessingContext processingContext) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public ManagedEntity<String, Booking> persist(String identifier,
                                                      Booking entity,
                                                      ProcessingContext processingContext) {
            return null;
        }

        @Override
        public ManagedEntity<String, Booking> attach(ManagedEntity<String, Booking> entity,
                                                     ProcessingContext processingContext) {
            return entity;
        }

        @Override
        public void describeTo(ComponentDescriptor descriptor) {
        }
    }

    static final class Booking {

    }
}
