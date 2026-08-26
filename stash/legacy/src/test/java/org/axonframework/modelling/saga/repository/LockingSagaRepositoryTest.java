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

import org.axonframework.common.lock.Lock;
import org.axonframework.common.lock.LockFactory;
import org.axonframework.messaging.unitofwork.CurrentUnitOfWork;
import org.axonframework.messaging.unitofwork.LegacyDefaultUnitOfWork;
import org.axonframework.messaging.unitofwork.LegacyUnitOfWork;
import org.axonframework.modelling.saga.AssociationValue;
import org.axonframework.modelling.saga.Saga;
import org.junit.jupiter.api.*;

import java.util.Collections;
import java.util.Set;
import java.util.function.Supplier;

import static org.mockito.Mockito.*;

/**
 * Test class validating the {@link LockingSagaRepository}.
 *
 * @author Rene de Waele
 */
class LockingSagaRepositoryTest {

    private LockFactory lockFactory;
    private Lock lock;
    private LockingSagaRepository<Object> subject;

    @BeforeEach
    void setUp() {
        lockFactory = mock(LockFactory.class);
        lock = mock(Lock.class);
        when(lockFactory.obtainLock(anyString())).thenReturn(lock);
        subject = spy(CustomSagaRepository.builder().lockFactory(lockFactory).build());
        LegacyDefaultUnitOfWork.startAndGet(null);
    }

    @AfterEach
    void tearDown() {
        CurrentUnitOfWork.ifStarted(LegacyUnitOfWork::commit);
    }

    @Test
    void lockReleasedOnUnitOfWorkCleanUpAfterCreate() {
        subject.createInstance("id", Object::new);
        verify(lockFactory).obtainLock("id");
        verify(subject).doCreateInstance(eq("id"), any());
        verifyNoInteractions(lock);
        CurrentUnitOfWork.commit();
        verify(lock).release();
    }

    @Test
    void lockReleasedOnUnitOfWorkCleanUpAfterLoad() {
        subject.load("id");
        verify(lockFactory).obtainLock("id");
        verify(subject).doLoad("id");
        verifyNoInteractions(lock);
        CurrentUnitOfWork.commit();
        verify(lock).release();
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
        public Set<String> find(AssociationValue associationValue) {
            return Collections.emptySet();
        }

        @Override
        protected Saga<Object> doLoad(String sagaIdentifier) {
            return saga;
        }

        @Override
        protected Saga<Object> doCreateInstance(String sagaIdentifier, Supplier<Object> factoryMethod) {
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
