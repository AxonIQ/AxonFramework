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

package org.axonframework.modelling.saga;

import org.axonframework.messaging.core.Scope;
import org.axonframework.messaging.core.ScopeDescriptor;

import java.util.Set;

/**
 * Abstract base class of a component that models a saga's life cycle.
 */
public abstract class SagaLifecycle extends Scope {

    /**
     * Registers a AssociationValue with the currently active saga. When the saga is committed, it can be found using
     * the registered property. If the saga already has the given association, nothing happens.
     *
     * @param associationKey   The key of the association value to associate this saga with.
     * @param associationValue The value of the association value to associate this saga with.
     */
    public static void associateWith(String associationKey, String associationValue) {
        associateWith(new AssociationValue(associationKey, associationValue));
    }

    /**
     * Registers a AssociationValue with the currently active saga. When the saga is committed, it can be found using
     * the registered property. The number value will be converted to a string. If the saga already has the given
     * association, nothing happens.
     *
     * @param associationKey   The key of the association value to associate this saga with.
     * @param associationValue The value of the association value to associate this saga with.
     */
    public static void associateWith(String associationKey, Number associationValue) {
        associateWith(new AssociationValue(associationKey, associationValue.toString()));
    }

    /**
     * Registers a AssociationValue with the currently active saga. When the saga is committed, it can be found using
     * the registered property. If the saga already has the given association, nothing happens.
     *
     * @param associationValue The association to associate this saga with.
     */
    public static void associateWith(AssociationValue associationValue) {
        getInstance().doAssociateWith(associationValue);
    }

    /**
     * Removes the given association from the currently active Saga. When the saga is committed, it can no longer be
     * found using the given association value. If the given saga wasn't associated with given values, nothing happens.
     *
     * @param associationKey   The key of the association value to remove from this saga.
     * @param associationValue The value of the association value to remove from this saga.
     */
    public static void removeAssociationWith(String associationKey, String associationValue) {
        getInstance().doRemoveAssociation(new AssociationValue(associationKey, associationValue));
    }

    /**
     * Removes the given association from the currently active Saga. When the saga is committed, it can no longer be
     * found using the given association value. If the given saga wasn't associated with given values, nothing happens.
     * The number value will be converted to a string.
     *
     * @param associationKey   The key of the association value to remove from this saga.
     * @param associationValue The value of the association value to remove from this saga.
     */
    public static void removeAssociationWith(String associationKey, Number associationValue) {
        removeAssociationWith(associationKey, associationValue.toString());
    }

    /**
     * Marks the saga as ended. Ended saga's may be cleaned up by the repository when they are committed.
     */
    public static void end() {
        getInstance().doEnd();
    }

    /**
     * Retrieves the {@link AssociationValue AssociationValues} that have been associated with the current Saga so far.
     * This includes the uncommitted ones, so adding or removing a {@link @link AssociationValue} through
     * {@link SagaLifecycle#associateWith(AssociationValue)} or any other method will have an immediate effect.
     *
     * @return The {@link AssociationValue AssociationValues} that have been associated with the Saga so far
     */
    public static Set<AssociationValue> associationValues() {
        return getInstance().getAssociationValues().asSet();
    }

    /**
     * {@link SagaLifecycle} instance method to mark the current saga as ended.
     */
    protected abstract void doEnd();

    /**
     * {@link SagaLifecycle} instance method to remove the given {@code associationValue}. If the current saga is not
     * associated with given value, this should do nothing.
     *
     * @param associationValue the association value to remove
     */
    protected abstract void doRemoveAssociation(AssociationValue associationValue);

    /**
     * {@link SagaLifecycle} instance method to register the given {@code associationValue}. If the current saga is
     * already associated with given value, this should do nothing.
     *
     * @param associationValue the association value to add
     */
    protected abstract void doAssociateWith(AssociationValue associationValue);

    /**
     * {@link SagaLifecycle} instance method to retrieve the {@link AssociationValues} of the currently active Saga.
     *
     * @return The {@link AssociationValues} of the current saga.
     */
    protected abstract AssociationValues getAssociationValues();

    /**
     * Get the current {@link SagaLifecycle} instance for the current thread. If none exists an {@link
     * IllegalStateException} is thrown.
     *
     * @return the thread's current {@link SagaLifecycle}
     */
    protected static SagaLifecycle getInstance() {
        return Scope.getCurrentScope();
    }

    /**
     * {@link SagaLifecycle} instance method to execute given {@code task} in the context of this SagaLifeCycle. This
     * update the thread's current saga lifecycle before executing the task. If a lifecycle is already registered with
     * the current thread that one will be temporarily replaced with this lifecycle until the task completes.
     *
     * @param task the task to execute
     */
    protected void execute(Runnable task) {
        try {
            executeWithResult(() -> {
                task.run();
                return null;
            });
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new SagaExecutionException("Exception while executing a task for a saga", e);
        }
    }

    /**
     * Retrieve a {@link String} denoting the type of this Saga.
     *
     * @return a {@link String} denoting the type of this Saga
     */
    protected abstract String type();

    /**
     * Retrieve a {@link String} denoting the identifier of this Saga.
     *
     * @return a {@link String} denoting the identifier of this Saga
     */
    protected abstract String getSagaIdentifier();

    @Override
    public ScopeDescriptor describeScope() {
        return new SagaScopeDescriptor(type(), getSagaIdentifier());
    }
}
