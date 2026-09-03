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

import org.axonframework.common.Assert;
import org.axonframework.common.infra.ComponentDescriptor;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.annotation.MessageHandlingMember;
import org.axonframework.messaging.core.interception.annotation.MessageHandlerInterceptorMemberChain;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.modelling.saga.metamodel.SagaModel;

import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Implementation of the {@link Saga interface} that allows for a POJO instance with annotated message handlers to act
 * as a Saga. The POJO instance can access {@link SagaLifecycle} operations, such as
 * {@link SagaLifecycle#associateWith(AssociationValue) associateWith} and {@link SagaLifecycle#end() end}, by declaring
 * a {@link SagaLifecycle}-typed parameter on its {@link SagaEventHandler @SagaEventHandler} method. This
 * {@code AnnotatedSaga} instance registers itself as the active {@link SagaLifecycle} on the {@link ProcessingContext}
 * for the duration of a single event handler invocation, so the parameter always resolves to this instance.
 * <p>
 * A {@link SagaEventHandler @SagaEventHandler} method must complete on the thread that invoked it. Returning a result
 * that is not done yet fails handling with a {@link SagaExecutionException}, because the Saga is stored in the
 * invoking thread's transaction and work continuing on another thread would fall outside it. Axon Framework 4 had no
 * way to express an asynchronous handler at all, so nothing that worked there is refused here.
 *
 * @author Allard Buijze
 * @since 3.0
 */
public class AnnotatedSaga<T> implements Saga<T>, SagaLifecycle {

    private final SagaModel<T> metaModel;
    private final MessageHandlerInterceptorMemberChain<T> chainedInterceptor;

    private final AssociationValues associationValues;
    private final String sagaId;
    private final T sagaInstance;
    private volatile boolean isActive = true;

    /**
     * Creates an AnnotatedSaga instance to wrap the given {@code annotatedSaga}, identifier with the given
     * {@code sagaId} and associated with the given {@code associationValues}. The {@code metaModel} provides the
     * description of the structure of the Saga.
     *
     * @param sagaId             The identifier of this Saga instance
     * @param associationValues  The current associations of this Saga
     * @param annotatedSaga      The object instance representing the Saga
     * @param metaModel          The model describing Saga structure
     * @param chainedInterceptor The interceptor to be used for this Saga
     */
    public AnnotatedSaga(String sagaId,
                         Set<AssociationValue> associationValues,
                         T annotatedSaga,
                         SagaModel<T> metaModel,
                         MessageHandlerInterceptorMemberChain<T> chainedInterceptor) {
        Assert.notNull(annotatedSaga, () -> "SagaInstance may not be null");
        this.sagaId = sagaId;
        this.associationValues = new AssociationValuesImpl(associationValues);
        this.sagaInstance = annotatedSaga;
        this.metaModel = metaModel;
        this.chainedInterceptor = chainedInterceptor;
    }

    @Override
    public String getSagaIdentifier() {
        return sagaId;
    }

    @Override
    public AssociationValues getAssociationValues() {
        return associationValues;
    }

    @Override
    public <R> R invoke(Function<T, R> invocation) {
        return invocation.apply(sagaInstance);
    }

    @Override
    public void execute(Consumer<T> invocation) {
        invocation.accept(sagaInstance);
    }

    @Override
    public boolean isActive() {
        return isActive;
    }

    @Override
    public Set<QualifiedName> supportedEvents() {
        throw new UnsupportedOperationException("TODO #3728");
    }

    @Override
    public Object sequenceIdentifierFor(EventMessage event, ProcessingContext context) {
        throw new UnsupportedOperationException("TODO #3728");
    }

    @Override
    public MessageStream.Empty<Message> handle(EventMessage event, ProcessingContext context) {
        if (!isActive) {
            return MessageStream.empty();
        }

        ProcessingContext sagaContext = context.withResource(SagaLifecycle.RESOURCE_KEY, this);
        return metaModel.findHandlerMethods(event, sagaContext)
                        .stream()
                        .filter(handler -> handler.unwrap(SagaMethodMessageHandlingMember.class)
                                                  .map(sh -> getAssociationValues()
                                                          .contains(sh.getAssociationValue(event)))
                                                  .orElse(true))
                        .findFirst()
                        .map(handler -> requireCompleted(
                                chainedInterceptor.handle(event, sagaContext, sagaInstance, handler)
                                                  .ignoreEntries()
                                                  .cast(),
                                handler
                        ))
                        .orElse(MessageStream.empty());
    }

    /**
     * Fails handling when the given {@code result} is not done yet, meaning the handler handed work to another thread.
     * <p>
     * Axon Framework 4 sagas could not do this: the handler's return value was ignored, so anything asynchronous it
     * started ran outside every unit of work and outside every transaction. Here the unit of work would await it, which
     * looks like support but moves the Saga's store write off the thread that owns the transaction, breaking the
     * mechanism by which a {@code SagaStore} joins it. Failing is what preserves the Axon Framework 4 contract.
     * <p>
     * This detects rather than prevents: the handler has already started whatever it started, and only the transaction
     * can still be rolled back. A handler that hands work to an executor and returns {@code void} is not detectable at
     * all, in Axon Framework 5 as much as in Axon Framework 4.
     */
    private MessageStream.Empty<Message> requireCompleted(MessageStream.Empty<Message> result,
                                                          MessageHandlingMember<? super T> handler) {
        if (result.isCompleted()) {
            return result;
        }
        result.close();
        return MessageStream.failed(new SagaExecutionException(
                "Handler [" + handler.signature() + "] of Saga [" + sagaId + "] returned a result that is not "
                        + "complete. A Saga event handler must complete on the thread that invoked it, since its Saga "
                        + "is stored in that thread's transaction."
        ));
    }

    /**
     * Returns the (annotated) Saga instance.
     *
     * @return the Saga instance
     */
    public T root() {
        return sagaInstance;
    }

    @Override
    public void associateWith(AssociationValue associationValue) {
        associationValues.add(associationValue);
    }

    @Override
    public void removeAssociationWith(AssociationValue associationValue) {
        associationValues.remove(associationValue);
    }

    @Override
    public void end() {
        isActive = false;
    }

    @Override
    public Set<AssociationValue> associationValues() {
        return associationValues.asSet();
    }

    @Override
    public void describeTo(ComponentDescriptor descriptor) {
        descriptor.describeProperty("sagaId", sagaId);
        descriptor.describeProperty("sagaInstance", sagaInstance);
        descriptor.describeProperty("active", isActive);
        descriptor.describeProperty("associationValues", associationValues);
        descriptor.describeProperty("metaModel", metaModel);
        descriptor.describeProperty("chainedInterceptor", chainedInterceptor);
    }
}
