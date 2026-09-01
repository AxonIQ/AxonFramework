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

import org.axonframework.messaging.core.Context;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;

import java.util.Objects;
import java.util.Set;

/**
 * Component giving a Saga's event handler methods access to lifecycle operations, such as managing
 * {@link AssociationValue AssociationValues} and ending the Saga.
 * <p>
 * This is the {@link ProcessingContext}-scoped replacement for the Axon Framework 4 {@code SagaLifecycle}, which
 * exposed the very same operations as {@code static} methods resolved through a {@code ThreadLocal}. Axon Framework
 * does not use {@code ThreadLocal}s to make state available to message handlers; it instead consistently passes
 * around the {@link ProcessingContext}. A {@code SagaLifecycle} instance is therefore only reachable through the
 * {@code ProcessingContext} that is active while a specific Saga instance handles a specific event.
 * <p>
 * To use this component, declare a {@code SagaLifecycle}-typed parameter on a {@link SagaEventHandler @SagaEventHandler}
 * method. The parameter is resolved automatically by the {@code SagaLifecycleParameterResolverFactory}:
 * <pre>{@code
 * @SagaEventHandler(associationProperty = "orderId")
 * public void on(OrderShippedEvent event, SagaLifecycle lifecycle) {
 *     lifecycle.associateWith("shipmentId", event.getShipmentId());
 *     lifecycle.end();
 * }
 * }</pre>
 *
 * @author Allard Buijze
 * @author Steven van Beelen
 * @since 5.4.0
 */
public interface SagaLifecycle {

    /**
     * The {@link Context.ResourceKey} under which the {@link SagaLifecycle} of the Saga instance currently handling
     * an event is registered on the {@link ProcessingContext}.
     */
    Context.ResourceKey<SagaLifecycle> RESOURCE_KEY = Context.ResourceKey.withLabel("sagaLifecycle");

    /**
     * Retrieves the {@link SagaLifecycle} registered for the given {@code context}.
     *
     * @param context the {@link ProcessingContext} to retrieve the active {@link SagaLifecycle} for
     * @return the {@link SagaLifecycle} active for the given {@code context}
     * @throws IllegalStateException if no {@link SagaLifecycle} is registered for the given {@code context}
     */
    static SagaLifecycle forContext(ProcessingContext context) {
        Objects.requireNonNull(context, "ProcessingContext may not be null");
        SagaLifecycle lifecycle = context.getResource(RESOURCE_KEY);
        if (lifecycle == null) {
            throw new IllegalStateException(
                    "No SagaLifecycle is active for the given ProcessingContext. A SagaLifecycle is only available "
                            + "while a Saga instance is handling an event."
            );
        }
        return lifecycle;
    }

    /**
     * Registers an {@link AssociationValue} with the current Saga. When the saga is committed, it can be found using
     * the registered property. If the saga already has the given association, nothing happens.
     *
     * @param associationKey   the key of the association value to associate this saga with
     * @param associationValue the value of the association value to associate this saga with
     */
    default void associateWith(String associationKey, String associationValue) {
        associateWith(new AssociationValue(associationKey, associationValue));
    }

    /**
     * Registers an {@link AssociationValue} with the current Saga. When the saga is committed, it can be found using
     * the registered property. The number value will be converted to a string. If the saga already has the given
     * association, nothing happens.
     *
     * @param associationKey   the key of the association value to associate this saga with
     * @param associationValue the value of the association value to associate this saga with
     */
    default void associateWith(String associationKey, Number associationValue) {
        associateWith(new AssociationValue(associationKey, associationValue.toString()));
    }

    /**
     * Registers the given {@code associationValue} with the current Saga. When the saga is committed, it can be
     * found using the registered property. If the saga already has the given association, nothing happens.
     *
     * @param associationValue the association to associate this saga with
     */
    void associateWith(AssociationValue associationValue);

    /**
     * Removes the given association from the current Saga. When the saga is committed, it can no longer be found
     * using the given association value. If the given saga wasn't associated with given values, nothing happens.
     *
     * @param associationKey   the key of the association value to remove from this saga
     * @param associationValue the value of the association value to remove from this saga
     */
    default void removeAssociationWith(String associationKey, String associationValue) {
        removeAssociationWith(new AssociationValue(associationKey, associationValue));
    }

    /**
     * Removes the given association from the current Saga. When the saga is committed, it can no longer be found
     * using the given association value. If the given saga wasn't associated with given values, nothing happens. The
     * number value will be converted to a string.
     *
     * @param associationKey   the key of the association value to remove from this saga
     * @param associationValue the value of the association value to remove from this saga
     */
    default void removeAssociationWith(String associationKey, Number associationValue) {
        removeAssociationWith(new AssociationValue(associationKey, associationValue.toString()));
    }

    /**
     * Removes the given {@code associationValue} from the current Saga. When the saga is committed, it can no longer
     * be found using the given association value. If the given saga wasn't associated with the given value, nothing
     * happens.
     *
     * @param associationValue the association value to remove from this saga
     */
    void removeAssociationWith(AssociationValue associationValue);

    /**
     * Marks the saga as ended. Ended sagas may be cleaned up by the repository when they are committed.
     */
    void end();

    /**
     * Retrieves the {@link AssociationValue AssociationValues} that have been associated with the current Saga so
     * far. This includes the uncommitted ones, so adding or removing an {@link AssociationValue} through
     * {@link #associateWith(AssociationValue)} or any other method will have an immediate effect.
     *
     * @return the {@link AssociationValue AssociationValues} that have been associated with the Saga so far
     */
    Set<AssociationValue> associationValues();
}
