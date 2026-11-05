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

package org.axonframework.eventsourcing.commandhandling.annotation;

import org.axonframework.common.AxonConfigurationException;
import org.axonframework.common.FutureUtils;
import org.axonframework.common.Priority;
import org.axonframework.common.annotation.AnnotationUtils;
import org.axonframework.common.annotation.Internal;
import org.axonframework.eventsourcing.commandhandling.AppendCriteriaInterceptor;
import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.core.Context.ResourceKey;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageHandlerInterceptorChain;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.annotation.HandlerEnhancerDefinition;
import org.axonframework.messaging.core.annotation.MessageHandlingMember;
import org.axonframework.messaging.core.annotation.WrappedMessageHandlingMember;
import org.axonframework.messaging.core.interception.annotation.InterceptorChainParameterResolverFactory;
import org.axonframework.messaging.core.interception.annotation.MessageHandlerInterceptorDefinition;
import org.axonframework.messaging.core.interception.annotation.MessageInterceptingMember;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventstreaming.EventCriteria;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * {@link HandlerEnhancerDefinition} turning {@link AppendCriteriaBuilder} methods into an
 * {@link AppendCriteriaInterceptor} for the commands they match.
 * <p>
 * An {@code AppendCriteriaBuilder} is a command handler interceptor, so the regular handler inspection discovers and
 * matches it. This definition only changes what invoking it means: instead of running the method before the handler,
 * the interceptor registers it as the command's
 * {@link org.axonframework.eventsourcing.commandhandling.AppendCriteriaResolver} and proceeds. The method runs when the
 * events are appended, with the sourcing criteria available to its {@link EventCriteria} parameter through
 * {@link SourcingCriteriaParameterResolverFactory}.
 * <p>
 * This definition is internal, as the framework picks it up from the classpath. Its high priority makes it wrap the
 * builder before the {@link MessageHandlerInterceptorDefinition} does, which leaves the wrapped member as it is.
 *
 * @author Mateusz Nowak
 * @since 5.5.0
 */
@Internal
@Priority(Priority.HIGH)
public class AppendCriteriaBuilderDefinition implements HandlerEnhancerDefinition {

    /**
     * The resource holding the sourcing criteria while an {@link AppendCriteriaBuilder} method is invoked.
     */
    static final ResourceKey<EventCriteria> SOURCING_CRITERIA = ResourceKey.withLabel("sourcingCriteria");

    @Override
    public <T> MessageHandlingMember<T> wrapHandler(MessageHandlingMember<T> original) {
        if (original.unwrap(AppendCriteriaBuilderMember.class).isPresent()) {
            // the flattened enhancer list may hold this definition more than once
            return original;
        }
        return original.unwrap(Method.class)
                       .filter(method -> AnnotationUtils.isAnnotationPresent(method, AppendCriteriaBuilder.class))
                       .<MessageHandlingMember<T>>map(method -> new AppendCriteriaBuilderMember<>(original, method))
                       .orElse(original);
    }

    private static final class AppendCriteriaBuilderMember<T>
            extends WrappedMessageHandlingMember<T>
            implements MessageInterceptingMember<T> {

        private final AppendCriteriaInterceptor interceptor = new AppendCriteriaInterceptor(this::invoke);

        private AppendCriteriaBuilderMember(MessageHandlingMember<T> delegate, Method method) {
            super(delegate);
            if (!Modifier.isStatic(method.getModifiers())
                    || !EventCriteria.class.isAssignableFrom(method.getReturnType())) {
                throw new AxonConfigurationException(
                        "An @AppendCriteriaBuilder method must be static and return EventCriteria. Violating method: "
                                + delegate.signature()
                );
            }
        }

        @Override
        public MessageStream<?> handle(Message message, ProcessingContext context, @Nullable T target) {
            MessageHandlerInterceptorChain<CommandMessage> chain =
                    InterceptorChainParameterResolverFactory.currentInterceptorChain(context);
            if (!(message instanceof CommandMessage command) || chain == null) {
                return MessageStream.failed(new IllegalStateException(
                        "An @AppendCriteriaBuilder only intercepts commands within an interceptor chain. Violating "
                                + "method: " + signature()
                ));
            }
            return interceptor.interceptOnHandle(command, context, chain);
        }

        private EventCriteria invoke(CommandMessage command, ProcessingContext context, EventCriteria sourcing) {
            var result = super.handle(command, context.withResource(SOURCING_CRITERIA, sourcing), null)
                              .first()
                              .asCompletableFuture();
            if (!result.isDone()) {
                // it runs inside the synchronous append condition override, so it cannot await a parameter
                throw new IllegalStateException(
                        "An @AppendCriteriaBuilder method must have parameters available without waiting. "
                                + "Violating method: " + signature()
                );
            }
            var entry = FutureUtils.joinAndUnwrap(result);
            if (entry == null || !(entry.message().payload() instanceof EventCriteria criteria)) {
                throw new IllegalStateException(
                        "An @AppendCriteriaBuilder method returned null. Violating method: " + signature()
                );
            }
            return criteria;
        }
    }
}
