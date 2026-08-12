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
package org.axonframework.messaging.queryhandling.annotation;

import org.axonframework.common.StringUtils;
import org.axonframework.common.infra.ComponentDescriptor;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.MessageTypeResolver;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.annotation.AnnotatedHandlerInspector;
import org.axonframework.messaging.core.annotation.HandlerDefinition;
import org.axonframework.messaging.core.annotation.ParameterResolverFactory;
import org.axonframework.messaging.core.conversion.MessageConverter;
import org.axonframework.messaging.core.interception.annotation.MessageHandlerInterceptorMemberChain;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.queryhandling.GenericQueryResponseMessage;
import org.axonframework.messaging.queryhandling.QueryBus;
import org.axonframework.messaging.queryhandling.QueryExecutionException;
import org.axonframework.messaging.queryhandling.QueryHandler;
import org.axonframework.messaging.queryhandling.QueryHandlingComponent;
import org.axonframework.messaging.queryhandling.QueryMessage;
import org.axonframework.messaging.queryhandling.QueryResponseMessage;
import org.axonframework.messaging.queryhandling.SimpleQueryHandlingComponent;

import java.util.Optional;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * Adapter that turns classes with {@link org.axonframework.messaging.queryhandling.annotation.QueryHandler} annotated
 * methods into a {@link QueryHandlingComponent}.
 * <p>
 * Each annotated method is subscribed as a {@link QueryHandler} at the {@link QueryHandlingComponent} with the query
 * name and response type specified by that method.
 *
 * @param <T> The target type of this query handling component.
 * @author Marc Gathier
 * @author Steven van Beelen
 * @since 3.1.0
 */
public class AnnotatedQueryHandlingComponent<T> implements QueryHandlingComponent {

    private final T target;
    private final SimpleQueryHandlingComponent handlingComponent;
    private final AnnotatedHandlerInspector<T> model;
    private final MessageTypeResolver messageTypeResolver;
    private final MessageConverter converter;

    /**
     * Wraps the given {@code annotatedQueryHandler}, allowing it to be subscribed to a {@link QueryBus} as a
     * {@link QueryHandlingComponent}.
     *
     * @param annotatedQueryHandler    The object containing the
     *                                 {@link org.axonframework.messaging.queryhandling.annotation.QueryHandler}
     *                                 annotated methods.
     * @param parameterResolverFactory The parameter resolver factory to resolve handler parameters with.
     * @param handlerDefinition        The handler definition used to create concrete handlers.
     * @param messageTypeResolver      The {@link MessageTypeResolver} resolving the {@link QualifiedName names} for
     *                                 {@link QueryMessage QueryMessages}.
     * @param converter                The converter to use for converting the payload of the query to the type expected
     *                                 by the handling method.
     */
    public AnnotatedQueryHandlingComponent(T annotatedQueryHandler,
                                           ParameterResolverFactory parameterResolverFactory,
                                           HandlerDefinition handlerDefinition,
                                           MessageTypeResolver messageTypeResolver,
                                           MessageConverter converter) {
        this.target = requireNonNull(annotatedQueryHandler, "The Annotated Query Handler may not be null.");
        this.handlingComponent = SimpleQueryHandlingComponent.create(
                "AnnotatedQueryHandlingComponent[%s]".formatted(annotatedQueryHandler.getClass().getName())
        );
        @SuppressWarnings("unchecked")
        Class<T> clazz = (Class<T>) annotatedQueryHandler.getClass();
        this.model = AnnotatedHandlerInspector.inspectType(
                clazz, messageTypeResolver, parameterResolverFactory, handlerDefinition
        );
        this.messageTypeResolver = requireNonNull(messageTypeResolver, "The MessageTypeResolver may not be null.");
        this.converter = requireNonNull(converter, "The MessageConverter may not be null.");

        initializeHandlersBasedOnModel();
    }

    private void initializeHandlersBasedOnModel() {
        model.getUniqueHandlers(target.getClass(), QueryMessage.class)
             .forEach(h -> registerHandler((QueryHandlingMember<? super T>) h));
    }

    private void registerHandler(QueryHandlingMember<? super T> handler) {
        Class<?> payloadType = handler.payloadType();
        QualifiedName qualifiedName = handler.unwrap(QueryHandlingMember.class)
                                             .map(QueryHandlingMember::queryName)
                                             // Filter empty Strings to  fall back to the MessageTypeResolver
                                             .filter(StringUtils::nonEmpty)
                                             .map(QualifiedName::new)
                                             .orElseGet(() -> messageTypeResolver.resolveOrThrow(payloadType)
                                                                                 .qualifiedName());

        handlingComponent.subscribe(qualifiedName, constructQueryHandlerFor(handler));
    }

    private QueryHandler constructQueryHandlerFor(QueryHandlingMember<? super T> handler) {
        MessageHandlerInterceptorMemberChain<T> interceptorChain = model.chainedInterceptor(target.getClass());
        return (query, context) -> {
            MessageStream<QueryResponseMessage> resultStream =
                    interceptorChain.handle(
                                            query.withConvertedPayload(handler.payloadType(), converter),
                                            context,
                                            target,
                                            handler
                                    )
                                    .mapMessage(this::asQueryResponseMessage);
            Optional<Throwable> handlingException = resultStream.error();
            if (handlingException.isPresent() && !(handlingException.get() instanceof QueryExecutionException)) {
                return MessageStream.failed(new QueryExecutionException(
                        "Handling query with identifier [" + query.identifier() + "] failed.",
                        handlingException.get()
                ));
            }
            return resultStream;
        };
    }

    private QueryResponseMessage asQueryResponseMessage(Message queryResponse) {
        return queryResponse instanceof QueryResponseMessage
                ? (QueryResponseMessage) queryResponse
                : new GenericQueryResponseMessage(queryResponse);
    }

    @Override
    public MessageStream<QueryResponseMessage> handle(QueryMessage query,
                                                      ProcessingContext context) {
        return handlingComponent.handle(query, context);
    }

    @Override
    public Set<QualifiedName> supportedQueries() {
        return Set.copyOf(handlingComponent.supportedQueries());
    }

    @Override
    public void describeTo(ComponentDescriptor descriptor) {
        descriptor.describeProperty("target", target);
        descriptor.describeWrapperOf(handlingComponent);
        descriptor.describeProperty("messageTypeResolver", messageTypeResolver);
        descriptor.describeProperty("converter", converter);
    }
}
