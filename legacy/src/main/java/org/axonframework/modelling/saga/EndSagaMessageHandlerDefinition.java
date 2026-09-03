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

import org.axonframework.messaging.core.MessageStream;
import org.axonframework.common.annotation.AnnotationUtils;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.annotation.HandlerEnhancerDefinition;
import org.axonframework.messaging.core.annotation.MessageHandlingMember;
import org.axonframework.messaging.core.annotation.WrappedMessageHandlingMember;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;

import java.lang.reflect.Executable;
import org.jspecify.annotations.Nullable;

/**
 * A {@link HandlerEnhancerDefinition} inspecting the existence of the {@link EndSaga} annotation on
 * {@link MessageHandlingMember}s. If present, the given {@code MessageHandlingMember} will be wrapped in a
 * {@link EndSageMessageHandlingMember}.
 *
 * @author Steven van Beelen
 * @since 4.5
 */
public class EndSagaMessageHandlerDefinition implements HandlerEnhancerDefinition {

    @Override
    public <T> MessageHandlingMember<T> wrapHandler(MessageHandlingMember<T> original) {
        return original.unwrap(Executable.class)
                       .filter(executable -> AnnotationUtils.isAnnotationPresent(executable, EndSaga.class))
                       .map(e -> (MessageHandlingMember<T>) new EndSageMessageHandlingMember<>(original))
                       .orElse(original);
    }

    /**
     * A {@link WrappedMessageHandlingMember} implementation dedicated towards {@link MessageHandlingMember}s annotated
     * with {@link EndSaga}. After invocation of the {@link #handle(Message, ProcessingContext, Object)} method, the saga's is ended
     * through the {@link SagaLifecycle#end()} method.
     *
     * @param <T> the entity type wrapped by this {@link MessageHandlingMember}
     */
    public static class EndSageMessageHandlingMember<T> extends WrappedMessageHandlingMember<T> {

        /**
         * Initializes the member using the given {@code delegate}.
         *
         * @param delegate the actual message handling member to delegate to
         */
        protected EndSageMessageHandlingMember(MessageHandlingMember<T> delegate) {
            super(delegate);
        }

        @Override
        public MessageStream<?> handle(Message message, ProcessingContext context, @Nullable T target) {
            return super.handle(message, context, target)
                        .onComplete(() -> SagaLifecycle.forContext(context).end());
        }
    }
}
