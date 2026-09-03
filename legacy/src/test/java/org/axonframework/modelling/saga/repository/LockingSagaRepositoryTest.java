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
import org.axonframework.common.lock.Lock;
import org.axonframework.common.lock.LockFactory;
import org.axonframework.common.lock.PessimisticLockFactory;
import org.axonframework.messaging.core.EmptyApplicationContext;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.SimpleUnitOfWorkFactory;
import org.axonframework.messaging.core.unitofwork.UnitOfWork;
import org.axonframework.messaging.core.unitofwork.UnitOfWorkConfiguration;
import org.axonframework.messaging.core.unitofwork.UnitOfWorkFactory;
import org.axonframework.modelling.saga.AssociationValue;
import org.axonframework.modelling.saga.Saga;
import org.junit.jupiter.api.*;

import java.time.Duration;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Test class validating the {@link LockingSagaRepository}.
 *
 * @author Rene de Waele
 */
class LockingSagaRepositoryTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private LockFactory lockFactory;
    private Lock lock;
    private LockingSagaRepository<Object> subject;
    private UnitOfWorkFactory unitOfWorkFactory;

    @BeforeEach
    void setUp() {
        lockFactory = mock(LockFactory.class);
        lock = mock(Lock.class);
        when(lockFactory.obtainLock(anyString())).thenReturn(lock);
        subject = spy(CustomSagaRepository.builder().lockFactory(lockFactory).build());
        unitOfWorkFactory = new SimpleUnitOfWorkFactory(EmptyApplicationContext.INSTANCE);
    }

    @Test
    void lockReleasedOnUnitOfWorkCleanUpAfterCreate() {
        UnitOfWork unitOfWork = unitOfWorkFactory.create();
        unitOfWork.runOnInvocation(context -> {
            subject.createInstance("id", Object::new, context);
            verify(lockFactory).obtainLock("id");
            verify(subject).doCreateInstance(eq("id"), any(), any());
            verifyNoInteractions(lock);
        });

        FutureUtils.joinAndUnwrap(unitOfWork.execute(), TIMEOUT);

        verify(lock).release();
    }

    @Test
    void lockReleasedOnUnitOfWorkCleanUpAfterLoad() {
        UnitOfWork unitOfWork = unitOfWorkFactory.create();
        unitOfWork.runOnInvocation(context -> {
            subject.load("id", context);
            verify(lockFactory).obtainLock("id");
            verify(subject).doLoad(eq("id"), any());
            verifyNoInteractions(lock);
        });

        FutureUtils.joinAndUnwrap(unitOfWork.execute(), TIMEOUT);

        verify(lock).release();
    }

    @Test
    void lockReleasedOnUnitOfWorkRollback() {
        UnitOfWork unitOfWork = unitOfWorkFactory.create();
        unitOfWork.runOnInvocation(context -> subject.load("id", context));
        unitOfWork.runOnPrepareCommit(context -> {
            throw new IllegalStateException("failing on purpose");
        });

        try {
            FutureUtils.joinAndUnwrap(unitOfWork.execute(), TIMEOUT);
        } catch (IllegalStateException expected) {
            // The unit of work fails, which is the point: the lock must be released either way.
        }

        verify(lock).release();
    }

    @Nested
    class WhenTwoUnitsOfWorkTargetTheSameSaga {

        @Test
        void theSecondOneWaitsForTheFirstToComplete() throws Exception {
            // given a repository using the real pessimistic locking strategy
            LockingSagaRepository<Object> repository =
                    CustomSagaRepository.builder().lockFactory(PessimisticLockFactory.usingDefaults()).build();
            CountDownLatch firstHoldsTheLock = new CountDownLatch(1);
            CountDownLatch releaseTheFirst = new CountDownLatch(1);
            CountDownLatch secondHoldsTheLock = new CountDownLatch(1);
            ExecutorService threads = Executors.newFixedThreadPool(2);

            try {
                // and a unit of work holding the lock on "id" until it is told to finish
                threads.submit(() -> {
                    UnitOfWork first = unitOfWorkFactory.create();
                    first.runOnInvocation(context -> {
                        repository.load("id", context);
                        firstHoldsTheLock.countDown();
                        awaitOrFail(releaseTheFirst);
                    });
                    return FutureUtils.joinAndUnwrap(first.execute(), TIMEOUT);
                });
                assertThat(firstHoldsTheLock.await(5, TimeUnit.SECONDS)).isTrue();

                // when a second unit of work goes for the same saga
                threads.submit(() -> {
                    UnitOfWork second = unitOfWorkFactory.create();
                    second.runOnInvocation(context -> {
                        repository.load("id", context);
                        secondHoldsTheLock.countDown();
                    });
                    return FutureUtils.joinAndUnwrap(second.execute(), TIMEOUT);
                });

                // then it does not get in while the first one is still running
                assertThat(secondHoldsTheLock.await(200, TimeUnit.MILLISECONDS)).isFalse();

                // and it gets in once the first one completes and its lock is released
                releaseTheFirst.countDown();
                assertThat(secondHoldsTheLock.await(5, TimeUnit.SECONDS)).isTrue();
            } finally {
                releaseTheFirst.countDown();
                threads.shutdownNow();
            }
        }
    }

    /**
     * The lock of the default {@link PessimisticLockFactory} is owned by the thread that acquired it, so it has to be
     * released on that same thread. Axon Framework 4 got that for free, because the unit of work lived in a thread
     * local. Here it depends on which thread the unit of work runs its completion handlers on.
     */
    @Nested
    class WhenAHandlerCompletesOnAnotherThread {

        @Test
        void theLockIsReleasedByThatOtherThread() {
            // given a unit of work that allows asynchronous processing, which is the default
            ThreadRecordingLockFactory recordingLockFactory = new ThreadRecordingLockFactory();
            LockingSagaRepository<Object> repository =
                    CustomSagaRepository.builder().lockFactory(recordingLockFactory).build();
            ExecutorService otherThread = Executors.newSingleThreadExecutor(r -> new Thread(r, "completing-thread"));
            CompletableFuture<Object> handlerResult = new CompletableFuture<>();

            try {
                UnitOfWork unitOfWork = unitOfWorkFactory.create();
                unitOfWork.onInvocation(context -> {
                    repository.load("id", context);
                    return handlerResult;
                });

                // when the handler's result is completed by another thread, after the unit of work has chained its
                // completion handlers onto it. Completing it any earlier is what makes this hazard intermittent in
                // production: an already completed result leaves the release on the invoking thread.
                CompletableFuture<Void> execution = unitOfWork.execute();
                otherThread.execute(() -> handlerResult.complete(null));
                FutureUtils.joinAndUnwrap(execution, TIMEOUT);

                // then the release ran on that thread rather than the one that took the lock. With the default
                // PessimisticLockFactory, ReentrantLock#unlock then throws IllegalMonitorStateException, which the
                // unit of work swallows as a warning, and the saga stays locked for the life of the JVM.
                assertThat(recordingLockFactory.acquiringThread()).isEqualTo(Thread.currentThread().getName());
                assertThat(recordingLockFactory.releasingThread()).isEqualTo("completing-thread");
            } finally {
                handlerResult.complete(null);
                otherThread.shutdownNow();
            }
        }

        @Test
        void sameThreadInvocationKeepsTheReleaseOnTheAcquiringThread() {
            // given a unit of work forced onto a single thread, as a transaction manager requiring same-thread
            // invocations arranges
            ThreadRecordingLockFactory recordingLockFactory = new ThreadRecordingLockFactory();
            LockingSagaRepository<Object> repository =
                    CustomSagaRepository.builder().lockFactory(recordingLockFactory).build();
            ExecutorService otherThread = Executors.newSingleThreadExecutor(r -> new Thread(r, "completing-thread"));
            CompletableFuture<Object> handlerResult = new CompletableFuture<>();
            CountDownLatch handlerInvoked = new CountDownLatch(1);

            try {
                UnitOfWork unitOfWork = unitOfWorkFactory.create(UnitOfWorkConfiguration::forcedSameThreadInvocation);
                unitOfWork.onInvocation(context -> {
                    repository.load("id", context);
                    handlerInvoked.countDown();
                    return handlerResult;
                });
                // This unit of work joins inside execute(), so the completion is scheduled up front rather than after.
                otherThread.execute(() -> {
                    awaitOrFail(handlerInvoked);
                    handlerResult.complete(null);
                });

                // when the handler's result is completed by another thread
                FutureUtils.joinAndUnwrap(unitOfWork.execute(), TIMEOUT);

                // then the unit of work waited for it and released the lock on the acquiring thread
                assertThat(recordingLockFactory.releasingThread())
                        .isEqualTo(recordingLockFactory.acquiringThread())
                        .isEqualTo(Thread.currentThread().getName());
            } finally {
                handlerResult.complete(null);
                otherThread.shutdownNow();
            }
        }
    }

    private static void awaitOrFail(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for the other unit of work");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /**
     * Hands out locks that do nothing but record which thread acquired and which thread released them.
     */
    private static class ThreadRecordingLockFactory implements LockFactory {

        private final AtomicReference<String> acquiringThread = new AtomicReference<>();
        private final AtomicReference<String> releasingThread = new AtomicReference<>();

        @Override
        public Lock obtainLock(String identifier) {
            acquiringThread.set(Thread.currentThread().getName());
            return new Lock() {
                @Override
                public void release() {
                    releasingThread.set(Thread.currentThread().getName());
                }

                @Override
                public boolean isHeld() {
                    return true;
                }
            };
        }

        private String acquiringThread() {
            return acquiringThread.get();
        }

        private String releasingThread() {
            return releasingThread.get();
        }
    }

    private static class CustomSagaRepository extends LockingSagaRepository<Object> {

        private final Saga<Object> saga;

        @SuppressWarnings("unchecked")
        private CustomSagaRepository(Builder builder) {
            super(builder);
            saga = mock(Saga.class);
        }

        public static Builder builder() {
            return new Builder();
        }

        @Override
        public Set<String> find(AssociationValue associationValue, ProcessingContext context) {
            return Collections.emptySet();
        }

        @Override
        protected Saga<Object> doLoad(String sagaIdentifier, ProcessingContext context) {
            return saga;
        }

        @Override
        protected Saga<Object> doCreateInstance(String sagaIdentifier,
                                                Supplier<Object> factoryMethod,
                                                ProcessingContext context) {
            return saga;
        }

        private static class Builder extends LockingSagaRepository.Builder<Object> {

            @Override
            public Builder lockFactory(LockFactory lockFactory) {
                super.lockFactory(lockFactory);
                return this;
            }

            public CustomSagaRepository build() {
                return new CustomSagaRepository(this);
            }
        }
    }
}
