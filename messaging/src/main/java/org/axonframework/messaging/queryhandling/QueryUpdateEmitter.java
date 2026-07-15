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

package org.axonframework.messaging.queryhandling;

import org.axonframework.common.infra.DescribableComponent;
import org.axonframework.conversion.ConversionException;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.MessageTypeNotResolvedException;
import org.axonframework.messaging.core.MessageTypeResolver;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.conversion.MessageConverter;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.EventHandler;
import org.jspecify.annotations.Nullable;

import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Query-specific component that interacts with
 * {@link QueryBus#subscriptionQuery(QueryMessage, ProcessingContext, int) subscription queries} about
 * {@link #emit(Class, Predicate, Object) update}, {@link #completeExceptionally(Class, Predicate, Throwable) errors},
 * and when there are {@link #complete(Class, Predicate) no more update}.
 * <p>
 * Implementations of the {@code QueryUpdateEmitter} are expected to be {@link ProcessingContext context-aware}, to
 * ensure operations occur within the correct order of (for example) the lifecycle of an
 * {@link EventHandler event handling function.}
 *
 * @author Milan Savic
 * @author Steven van Beelen
 * @since 3.3.0
 */
public interface QueryUpdateEmitter extends DescribableComponent {

    /**
     * Creates a query update emitter for the given {@link ProcessingContext}.
     * <p>
     * Use this from within a message handler, or any other method that receives a {@link ProcessingContext}, instead
     * of emitting directly through a {@link QueryBus}: the emitter returned here is bound to that {@code context},
     * ensuring updates are emitted in the correct order relative to the lifecycle of whatever is currently being
     * handled.
     * <p>
     * Every invocation returns a fresh instance bound to the given {@code context} - it is never cached or shared
     * with another call, even for the same {@code context}. Use the returned emitter only for operations belonging
     * to that {@code context}: if {@code context} is a branch created via {@link ProcessingContext#withResource}
     * (e.g. one such branch per event in a streaming processor's batch), each branch must resolve its own emitter
     * rather than risk one branch's emitter leaking into another's.
     *
     * @param context The {@link ProcessingContext} to create the emitter for.
     * @return A fresh emitter specific for the given {@code context}.
     */
    static QueryUpdateEmitter forContext(ProcessingContext context) {
        return new SimpleQueryUpdateEmitter(
                context.component(QueryBus.class),
                context.component(MessageTypeResolver.class),
                context.component(MessageConverter.class),
                context
        );
    }

    /**
     * Emits given {@code update} to subscription queries matching the given {@code queryType} and given
     * {@code filter}.
     *
     * @param queryType The type of the {@link QueryMessage} to filter on.
     * @param filter    A predicate to filter matching subscription queries based on the {@link QueryMessage#payload()}
     *                  converted to the given {@code queryType}.
     * @param update    The incremental update to emit for
     *                  {@link QueryBus#subscriptionQuery(QueryMessage, ProcessingContext, int) subscription
     *                  queries} matching the given {@code filter}.
     * @param <Q>       The type of the {@link QueryMessage} to filter on.
     * @throws MessageTypeNotResolvedException                     If the given {@code queryType} has no known
     *                                                             {@link MessageType}
     *                                                             equivalent required to filter the
     *                                                             {@link QueryMessage#payload()}.
     * @throws ConversionException If the {@link QueryMessage#payload()}
     *                                                             could not be converted to the given {@code queryType}
     *                                                             to perform the given {@code filter}. Will only occur
     *                                                             if a {@link MessageType}
     *                                                             could be found for the given {@code queryType}.
     */
    default <Q> void emit(Class<Q> queryType, Predicate<? super Q> filter, @Nullable Object update) {
        emit(queryType, filter, () -> update);
    }

    /**
     * Emits the outcome of the {@code updateSupplier} to subscription queries matching the given {@code queryType} and
     * given {@code filter}.
     * <p>
     * The {@code updateSupplier} is only invoked whenever there are matching queries.
     *
     * @param queryType      The type of the {@link QueryMessage} to filter on.
     * @param filter         A predicate to filter matching subscription queries based on the {@link QueryMessage#payload()}
     *                       converted to the given {@code queryType}
     * @param updateSupplier The update supplier to emit for
     *                       {@link QueryBus#subscriptionQuery(QueryMessage, ProcessingContext, int)
     *                       subscription queries} matching the given {@code queryType} and {@code filter}.
     * @param <Q>            The type of the {@link QueryMessage} to filter on.
     * @throws MessageTypeNotResolvedException                     If the given {@code queryType} has no known
     *                                                             {@link MessageType}
     *                                                             equivalent required to filter the
     *                                                             {@link QueryMessage#payload()}.
     * @throws ConversionException If the {@link QueryMessage#payload()}
     *                                                             could not be converted to the given {@code queryType}
     *                                                             to perform the given {@code filter}. Will only occur
     *                                                             if a {@link MessageType}
     *                                                             could be found for the given {@code queryType}.
     */
    <Q> void emit(Class<Q> queryType,
                  Predicate<? super Q> filter,
                  Supplier<Object> updateSupplier);

    /**
     * Emits given {@code update} to subscription queries matching the given {@code queryName} and given
     * {@code filter}.
     *
     * @param queryName The qualified name of the {@link QueryMessage#type()} to filter on.
     * @param filter    A predicate to filter matching subscription queries based on the raw
     *                  {@link QueryMessage#payload()}.
     * @param update    The incremental update to emit for
     *                  {@link QueryBus#subscriptionQuery(QueryMessage, ProcessingContext, int) subscription
     *                  queries} matching the given {@code filter}.
     */
    default void emit(QualifiedName queryName, Predicate<Object> filter, @Nullable Object update) {
        emit(queryName, filter, () -> update);
    }

    /**
     * Emits the outcome of the {@code updateSupplier} to subscription queries matching the given {@code queryName} and
     * given {@code filter}.
     * <p>
     * The {@code updateSupplier} is only invoked whenever there are matching queries.
     *
     * @param queryName      The qualified name of the {@link QueryMessage#type()} to filter on.
     * @param filter         A predicate to filter matching subscription queries based on the raw
     *                       {@link QueryMessage#payload()}.
     * @param updateSupplier The update supplier to emit for
     *                       {@link QueryBus#subscriptionQuery(QueryMessage, ProcessingContext, int)
     *                       subscription queries} matching the given {@code queryName} and {@code filter}.
     */
    void emit(QualifiedName queryName,
              Predicate<Object> filter,
              Supplier<Object> updateSupplier);

    /**
     * Completes subscription queries matching the given {@code queryType} and {@code filter}.
     *
     * @param queryType The type of the {@link QueryMessage} to filter on.
     * @param filter    A predicate to filter matching subscription queries based on the {@link QueryMessage#payload()}
     *                  converted to the given {@code queryType}
     * @param <Q>       The type of the {@link QueryMessage} to filter on.
     * @throws MessageTypeNotResolvedException                     If the given {@code queryType} has no known
     *                                                             {@link MessageType}
     *                                                             equivalent required to filter the
     *                                                             {@link QueryMessage#payload()}.
     * @throws ConversionException If the {@link QueryMessage#payload()}
     *                                                             could not be converted to the given {@code queryType}
     *                                                             to perform the given {@code filter}. Will only occur
     *                                                             if a {@link MessageType}
     *                                                             could be found for the given {@code queryType}.
     */
    <Q> void complete(Class<Q> queryType, Predicate<? super Q> filter);

    /**
     * Completes subscription queries matching the given {@code queryName} and {@code filter}.
     *
     * @param queryName The qualified name of the {@link QueryMessage#type()} to filter on.
     * @param filter    A predicate testing the raw {@link QueryMessage#payload()} as is.
     */
    void complete(QualifiedName queryName, Predicate<Object> filter);

    /**
     * Completes subscription queries with the given {@code cause} matching given {@code queryType} and {@code filter}.
     *
     * @param queryType The type of the {@link QueryMessage} to filter on.
     * @param filter    A predicate to filter matching subscription queries based on the {@link QueryMessage#payload()}
     *                  converted to the given {@code queryType}
     * @param cause     The cause of an error leading to exceptionally complete subscription queries.
     * @param <Q>       The type of the {@link QueryMessage} to filter on.
     * @throws MessageTypeNotResolvedException                     If the given {@code queryType} has no known
     *                                                             {@link MessageType}
     *                                                             equivalent required to filter the
     *                                                             {@link QueryMessage#payload()}.
     * @throws ConversionException If the {@link QueryMessage#payload()}
     *                                                             could not be converted to the given {@code queryType}
     *                                                             to perform the given {@code filter}. Will only occur
     *                                                             if a {@link MessageType}
     *                                                             could be found for the given {@code queryType}.
     */
    <Q> void completeExceptionally(Class<Q> queryType,
                                   Predicate<? super Q> filter,
                                   Throwable cause);

    /**
     * Completes subscription queries with the given {@code cause} matching given {@code queryName} and {@code filter}.
     *
     * @param queryName The qualified name of the {@link QueryMessage#type()} to filter on.
     * @param filter    A predicate to filter matching subscription queries based on the raw
     *                  {@link QueryMessage#payload()}.
     * @param cause     The cause of an error leading to exceptionally complete subscription queries.
     */
    void completeExceptionally(QualifiedName queryName,
                               Predicate<Object> filter,
                               Throwable cause);
}
