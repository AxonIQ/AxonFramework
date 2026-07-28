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

import org.axonframework.common.FutureUtils;
import org.axonframework.common.infra.ComponentDescriptor;
import org.axonframework.conversion.Converter;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.MessageTypeResolver;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.conversion.MessageConverter;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Type;
import java.util.OptionalInt;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static java.util.Objects.requireNonNull;

/**
 * Simple implementation of the {@link QueryUpdateEmitter}, delegating operations to a {@link QueryBus} for emitting
 * update, completing subscription queries, and completing subscription queries exceptionally.
 * <p>
 * Uses the {@link ProcessingContext} given during construction of the {@code SimpleQueryUpdateEmitter} to perform all
 * operations in the expected lifecycle order.
 *
 * @author Milan Savic
 * @author Stefan Dragisic
 * @author Steven van Beelen
 * @since 4.0.0
 */
public class SimpleQueryUpdateEmitter implements QueryUpdateEmitter {

    private static final Logger logger = LoggerFactory.getLogger(SimpleQueryUpdateEmitter.class);

    private final QueryBus queryBus;
    private final MessageTypeResolver messageTypeResolver;
    private final MessageConverter converter;
    private final ProcessingContext context;

    /**
     * Construct a {@code SimpleQueryUpdateEmitter} with the given {@code messageTypeResolver}, {@code queryBus}, and
     * {@code context}.
     * <p>
     * The {@code messageTypeResolver} is used to construct {@link SubscriptionQueryUpdateMessage update messages} for
     * {@link #emit(Class, Predicate, Object)} invocations. Any {@link #emit(Class, Predicate, Object)} or
     * {@link #emit(QualifiedName, Predicate, Object)} is delegated to
     * {@link QueryBus#emitUpdate(Predicate, Supplier, ProcessingContext)}, while their match-count-returning
     * counterparts, {@link #emitAndCount(Class, Predicate, Supplier)} and
     * {@link #emitAndCount(QualifiedName, Predicate, Supplier)}, are delegated to
     * {@link QueryBus#emitUpdateAndCount(Predicate, Supplier, ProcessingContext)}. Similarly,
     * {@link #complete(Class, Predicate)}/{@link #complete(QualifiedName, Predicate)} and
     * {@link #completeExceptionally(Class, Predicate, Throwable)}/{@link #completeExceptionally(QualifiedName,
     * Predicate, Throwable)} are respectively delegated to
     * {@link QueryBus#completeSubscriptions(Predicate, ProcessingContext)} and
     * {@link QueryBus#completeSubscriptionsExceptionally(Predicate, Throwable, ProcessingContext)}, with their
     * match-count-returning counterparts delegated to
     * {@link QueryBus#completeSubscriptionsAndCount(Predicate, ProcessingContext)} and
     * {@link QueryBus#completeSubscriptionsExceptionallyAndCount(Predicate, Throwable, ProcessingContext)}.
     *
     * @param queryBus            The {@code QueryBus} to delegate the {@link #emit(Class, Predicate, Object)},
     *                            {@link #complete(Class, Predicate)}, and
     *                            {@link #completeExceptionally(Class, Predicate, Throwable)} invocations to.
     * @param messageTypeResolver The {@link MessageType} resolver used to construct
     *                            {@link SubscriptionQueryUpdateMessage update messages} for
     *                            {@link #emit(Class, Predicate, Object)} invocations
     * @param converter           The {@code MessageConverter} used to convert the
     *                            {@link Message#payloadAs(Type, Converter) payload} whenever a filter is used based on
     *                            a concrete type. For example, through {@link #emit(Class, Predicate, Object)}.
     * @param context             The {@code ProcessingContext} within which update are emitted, subscription query are
     *                            completed, and subscription queries are completed exceptionally in.
     */
    public SimpleQueryUpdateEmitter(QueryBus queryBus,
                                    MessageTypeResolver messageTypeResolver,
                                    MessageConverter converter,
                                    ProcessingContext context) {
        this.queryBus = requireNonNull(queryBus, "The QueryBus must not be null.");
        this.messageTypeResolver = requireNonNull(messageTypeResolver, "The MessageTypeResolver must not be null.");
        this.converter = requireNonNull(converter, "The MessageConverter must not be null.");
        this.context = requireNonNull(context, "The ProcessingContext must not be null.");
    }

    @Override
    public <Q> void emit(Class<Q> queryType,
                         Predicate<? super Q> filter,
                         Supplier<Object> updateSupplier) {
        if (logger.isDebugEnabled()) {
            logger.debug("Emitting an update to queries matching type [{}] and a given filter.", queryType);
        }
        queryBus.emitUpdate(queryTypeFilter(queryType, filter), () -> asUpdateMessage(updateSupplier.get()), context)
                .join();
    }

    @SuppressWarnings("DataFlowIssue")
    @Override
    public <Q> OptionalInt emitAndCount(Class<Q> queryType,
                                        Predicate<? super Q> filter,
                                        Supplier<Object> updateSupplier) {
        if (logger.isDebugEnabled()) {
            logger.debug(
                    "Emitting an update to queries matching type [{}] and a given filter, returning the match count.",
                    queryType
            );
        }
        return FutureUtils.joinAndUnwrap(queryBus.emitUpdateAndCount(
                queryTypeFilter(queryType, filter),
                () -> asUpdateMessage(updateSupplier.get()),
                context
        ));
    }

    @Override
    public void emit(QualifiedName queryName,
                     Predicate<Object> filter,
                     Supplier<Object> updateSupplier) {
        if (logger.isDebugEnabled()) {
            logger.debug("Emitting an update to queries matching name [{}] and a given filter.", queryName);
        }
        queryBus.emitUpdate(queryNameFilter(queryName, filter), () -> asUpdateMessage(updateSupplier.get()), context)
                .join();
    }

    @SuppressWarnings("DataFlowIssue")
    @Override
    public OptionalInt emitAndCount(QualifiedName queryName,
                                    Predicate<Object> filter,
                                    Supplier<Object> updateSupplier) {
        if (logger.isDebugEnabled()) {
            logger.debug(
                    "Emitting an update to queries matching name [{}] and a given filter, returning the match count.",
                    queryName
            );
        }
        return FutureUtils.joinAndUnwrap(queryBus.emitUpdateAndCount(
                queryNameFilter(queryName, filter),
                () -> asUpdateMessage(updateSupplier.get()),
                context
        ));
    }

    private SubscriptionQueryUpdateMessage asUpdateMessage(Object update) {
        if (update instanceof SubscriptionQueryUpdateMessage updateMessage) {
            return updateMessage;
        }
        return update instanceof Message updateMessage
                ? new GenericSubscriptionQueryUpdateMessage(updateMessage)
                : new GenericSubscriptionQueryUpdateMessage(messageTypeResolver.resolveOrThrow(update), update);
    }

    @Override
    public <Q> void complete(Class<Q> queryType, Predicate<? super Q> filter) {
        if (logger.isDebugEnabled()) {
            logger.debug("Completing subscription queries of type [{}].", queryType);
        }
        queryBus.completeSubscriptions(queryTypeFilter(queryType, filter), context)
                .join();
    }

    @SuppressWarnings("DataFlowIssue")
    @Override
    public <Q> OptionalInt completeAndCount(Class<Q> queryType, Predicate<? super Q> filter) {
        if (logger.isDebugEnabled()) {
            logger.debug("Completing subscription queries of type [{}], returning the match count.", queryType);
        }
        return FutureUtils.joinAndUnwrap(
                queryBus.completeSubscriptionsAndCount(queryTypeFilter(queryType, filter), context)
        );
    }

    @Override
    public void complete(QualifiedName queryName, Predicate<Object> filter) {
        if (logger.isDebugEnabled()) {
            logger.debug("Completing subscription queries with name [{}].", queryName);
        }
        queryBus.completeSubscriptions(queryNameFilter(queryName, filter), context)
                .join();
    }

    @SuppressWarnings("DataFlowIssue")
    @Override
    public OptionalInt completeAndCount(QualifiedName queryName, Predicate<Object> filter) {
        if (logger.isDebugEnabled()) {
            logger.debug("Completing subscription queries with name [{}], returning the match count.", queryName);
        }
        return FutureUtils.joinAndUnwrap(
                queryBus.completeSubscriptionsAndCount(queryNameFilter(queryName, filter), context)
        );
    }

    @Override
    public <Q> void completeExceptionally(Class<Q> queryType,
                                          Predicate<? super Q> filter,
                                          Throwable cause) {
        if (logger.isDebugEnabled()) {
            logger.debug("Completing subscription queries of type [{}] exceptionally.", queryType, cause);
        }
        queryBus.completeSubscriptionsExceptionally(queryTypeFilter(queryType, filter), cause, context)
                .join();
    }

    @SuppressWarnings("DataFlowIssue")
    @Override
    public <Q> OptionalInt completeExceptionallyAndCount(Class<Q> queryType,
                                                         Predicate<? super Q> filter,
                                                         Throwable cause) {
        if (logger.isDebugEnabled()) {
            logger.debug("Completing subscription queries of type [{}] exceptionally, returning the match count.",
                         queryType, cause);
        }
        return FutureUtils.joinAndUnwrap(
                queryBus.completeSubscriptionsExceptionallyAndCount(queryTypeFilter(queryType, filter), cause, context)
        );
    }

    @Override
    public void completeExceptionally(QualifiedName queryName,
                                      Predicate<Object> filter,
                                      Throwable cause) {
        if (logger.isDebugEnabled()) {
            logger.debug("Completing subscription queries with name [{}] exceptionally.", queryName, cause);
        }
        queryBus.completeSubscriptionsExceptionally(queryNameFilter(queryName, filter), cause, context)
                .join();
    }

    @SuppressWarnings("DataFlowIssue")
    @Override
    public OptionalInt completeExceptionallyAndCount(QualifiedName queryName,
                                                     Predicate<Object> filter,
                                                     Throwable cause) {
        if (logger.isDebugEnabled()) {
            logger.debug("Completing subscription queries with name [{}] exceptionally, returning the match count.",
                         queryName, cause);
        }
        return FutureUtils.joinAndUnwrap(
                queryBus.completeSubscriptionsExceptionallyAndCount(queryNameFilter(queryName, filter), cause, context)
        );
    }

    private static Predicate<QueryMessage> queryNameFilter(QualifiedName queryName,
                                                           Predicate<Object> filter) {
        return message -> queryName.equals(message.type().qualifiedName()) && filter.test(message.payload());
    }

    private <Q> Predicate<QueryMessage> queryTypeFilter(Class<Q> queryType,
                                                        Predicate<? super Q> filter) {
        return message -> {
            QualifiedName queryName = messageTypeResolver.resolveOrThrow(queryType).qualifiedName();
            return queryName.equals(message.type().qualifiedName())
                    && filter.test(message.payloadAs(queryType, converter));
        };
    }

    @Override
    public void describeTo(ComponentDescriptor descriptor) {
        descriptor.describeProperty("queryBus", queryBus);
        descriptor.describeProperty("messageTypeResolver", messageTypeResolver);
        descriptor.describeProperty("converter", converter);
        descriptor.describeProperty("context", context);
    }
}
