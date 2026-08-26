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

import org.axonframework.messaging.eventhandling.EventMessageHandler;
import org.axonframework.messaging.eventhandling.replay.ResetNotSupportedException;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;

import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Interface describing an implementation of a Saga. Sagas are instances that handle events and may possibly produce
 * new commands or have other side effects. Typically, Sagas are used to manage long running business transactions.
 * <p/>
 * Multiple instances of a single type of Saga may exist. In that case, each Saga will be managing a different
 * transaction. Sagas need to be associated with concepts in order to receive specific events. These associations are
 * managed through AssociationValues. For example, to associate a saga with an Order with ID 1234, this saga needs an
 * association value with key {@code "orderId"} and value {@code "1234"}.
 *
 * @author Allard Buijze
 * @since 0.7
 */
public interface Saga<T> extends EventMessageHandler {

    /**
     * Returns the unique identifier of this saga.
     *
     * @return the unique identifier of this saga
     */
    String getSagaIdentifier();

    /**
     * Returns a view on the Association Values for this saga instance. The returned instance is mutable.
     *
     * @return a view on the Association Values for this saga instance
     */
    AssociationValues getAssociationValues();

    /**
     * Execute the given {@code invocation} against the root object of this Saga instance.
     *
     * @param invocation the function to invoke. The root object of the Saga is input to the function, the result is
     *                   the result of the execution.
     * @param <R>        The type of return value expected
     * @return The result of the invocation on the Saga.
     */
    <R> R invoke(Function<T, R> invocation);

    /**
     * Execute the given {@code invocation} against the root object of this Saga instance.
     *
     * @param invocation the function to invoke. The root object of the Saga is input to the function.
     */
    void execute(Consumer<T> invocation);


    /**
     * Indicates whether or not this saga is active. A Saga is active when its life cycle has not been ended.
     *
     * @return {@code true} if this saga is active, {@code false} otherwise.
     */
    boolean isActive();

    @Override
    default boolean supportsReset() {
        return false;
    }

    @Override
    default void prepareReset(ProcessingContext processingContext) {
        prepareReset(null, processingContext);
    }

    @Override
    default void prepareReset(Object resetContext, ProcessingContext processingContext) {
        throw new ResetNotSupportedException("Sagas do not support reset");
    }
}
