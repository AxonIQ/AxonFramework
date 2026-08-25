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
import org.axonframework.common.lock.Lock;
import org.axonframework.common.lock.LockFactory;
import org.axonframework.common.lock.PessimisticLockFactory;
import org.axonframework.messaging.unitofwork.CurrentUnitOfWork;
import org.axonframework.messaging.unitofwork.LegacyUnitOfWork;
import org.axonframework.modelling.saga.Saga;
import org.axonframework.modelling.saga.SagaRepository;

import java.util.function.Supplier;

import static org.axonframework.common.BuilderUtils.assertNonNull;

/**
 * Abstract implementation of a saga repository that locks access to a saga while the saga is being operated on.
 *
 * @author Rene de Waele
 * @since 3.0
 */
public abstract class LockingSagaRepository<T> implements SagaRepository<T> {

    private final LockFactory lockFactory;

    /**
     * Instantiate a {@link LockingSagaRepository} based on the fields contained in the {@link Builder}.
     * <p>
     * Will assert that the {@link LockFactory} is not {@code null}. Will throw an {@link AxonConfigurationException} if
     * it is.
     *
     * @param builder the {@link Builder} used to instantiate a {@link LockingSagaRepository} instance
     */
    protected LockingSagaRepository(Builder<T> builder) {
        builder.validate();
        this.lockFactory = builder.lockFactory;
    }

    /**
     * {@inheritDoc}
     * <p>
     * This implementation locks access to sagas with the given {@code sagaIdentifier} and releases the lock in the
     * clean-up phase of the current {@link LegacyUnitOfWork}.
     */
    @Override
    public Saga<T> load(String sagaIdentifier) {
        lockSagaAccess(sagaIdentifier);
        return doLoad(sagaIdentifier);
    }

    /**
     * {@inheritDoc}
     * <p>
     * This implementation locks access to sagas with the given {@code sagaIdentifier} and releases the lock in the
     * clean-up phase of the current {@link LegacyUnitOfWork}.
     */
    @Override
    public Saga<T> createInstance(String sagaIdentifier, Supplier<T> factoryMethod) {
        lockSagaAccess(sagaIdentifier);
        return doCreateInstance(sagaIdentifier, factoryMethod);
    }

    private void lockSagaAccess(String sagaIdentifier) {
        LegacyUnitOfWork<?> unitOfWork = CurrentUnitOfWork.get();
        Lock lock = lockFactory.obtainLock(sagaIdentifier);
        unitOfWork.root().onCleanup(u -> lock.release());
    }

    /**
     * Loads a known Saga instance by its unique identifier. Due to the concurrent nature of Sagas, it is not unlikely
     * for a Saga to have ceased to exist after it has been found based on associations. Therefore, a repository should
     * return {@code null} in case a Saga doesn't exists, as opposed to throwing an exception.
     *
     * @param sagaIdentifier The unique identifier of the Saga to load
     * @return The Saga instance, or {@code null} if no such saga exists
     */
    protected abstract Saga<T> doLoad(String sagaIdentifier);

    /**
     * Creates a new Saga instance. The returned Saga will delegate event handling to the instance supplied by the given
     * {@code factoryMethod}.
     *
     * @param sagaIdentifier the identifier to use for the new saga instance
     * @param factoryMethod  Used to create a new Saga delegate
     * @return a new Saga instance wrapping an instance of type {@code T}
     */
    protected abstract Saga<T> doCreateInstance(String sagaIdentifier, Supplier<T> factoryMethod);

    /**
     * Abstract Builder class to instantiate {@link LockingSagaRepository} implementations.
     * <p>
     * The {@link LockFactory} is defaulted to a pessimistic locking strategy, implemented in the {@link
     * PessimisticLockFactory}.
     *
     * @param <T> a generic specifying the Saga type contained in this {@link SagaRepository} implementation
     */
    public abstract static class Builder<T> {

        private LockFactory lockFactory = PessimisticLockFactory.usingDefaults();

        /**
         * Sets the {@link LockFactory} used to lock a saga. Defaults to a pessimistic locking strategy, implemented in
         * the {@link PessimisticLockFactory}.
         *
         * @param lockFactory a {@link LockFactory} used to lock an aggregate
         * @return the current Builder instance, for fluent interfacing
         */
        public Builder<T> lockFactory(LockFactory lockFactory) {
            assertNonNull(lockFactory, "LockFactory may not be null");
            this.lockFactory = lockFactory;
            return this;
        }

        /**
         * Validates whether the fields contained in this Builder are set accordingly.
         *
         * @throws AxonConfigurationException if one field is asserted to be incorrect according to the Builder's
         *                                    specifications
         */
        protected void validate() throws AxonConfigurationException {
            // Method kept for overriding
        }
    }
}
