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

package org.axonframework.messaging.core.interception.annotation;

import org.axonframework.common.AxonConfigurationException;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageHandlerInterceptorChain;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.MessageStream.Entry;
import org.axonframework.messaging.core.annotation.HandlerAttributes;
import org.axonframework.messaging.core.annotation.HandlerEnhancerDefinition;
import org.axonframework.messaging.core.annotation.MessageHandlingMember;
import org.axonframework.messaging.core.annotation.WrappedMessageHandlingMember;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * {@link HandlerEnhancerDefinition} that marks methods (meta-)annotated with {@link MessageHandlerInterceptor} as
 * interceptors. These methods need to be given special treatment when invoking handlers. Matching interceptors need to
 * be invoked first, allowing them to proceed the invocation chain.
 * <p>
 * This definition also recognizes interceptors only acting on the response. These must be meta-annotated with
 * {@link ResultHandler}.
 *
 * @author Allard Buijze
 * @since 4.4.0
 */
public class MessageHandlerInterceptorDefinition implements HandlerEnhancerDefinition {

    @Override
    public <T> MessageHandlingMember<T> wrapHandler(MessageHandlingMember<T> original) {
        if (original.attribute(HandlerAttributes.INTERCEPTOR_MESSAGE_TYPE).isPresent()) {
            Optional<Class<?>> resultType = original.attribute(HandlerAttributes.RESULT_TYPE);
            return resultType.isPresent()
                    ? new ResultHandlingInterceptorMember<>(original, resultType.get())
                    : new InterceptedMessageHandlingMember<>(original);
        }
        return original;
    }

    private static class ResultHandlingInterceptorMember<T>
            extends WrappedMessageHandlingMember<T>
            implements MessageInterceptingMember<T> {

        private static final Logger logger =
                LoggerFactory.getLogger(ResultHandlingInterceptorMember.class);
        private final Class<?> expectedResultType;

        public ResultHandlingInterceptorMember(MessageHandlingMember<T> original, Class<?> expectedResultType) {
            super(original);
            this.expectedResultType = expectedResultType;
            Method method = original.unwrap(Method.class).orElseThrow(() -> new AxonConfigurationException(
                    "Only methods can be marked as MessageHandlerInterceptor. "
                            + "Violating handler: " + original.signature())
            );
            boolean declaredInterceptorChain =
                    Arrays.stream(method.getParameters())
                          .anyMatch(p -> p.getType().equals(MessageHandlerInterceptorChain.class));
            if (declaredInterceptorChain) {
                throw new AxonConfigurationException(
                        "A MessageHandlerInterceptor acting on the invocation result must not "
                                + "declare a parameter of type InterceptorChain. "
                                + "Violating handler: " + original.signature()
                );
            }
        }

        @Override
        public int priority() {
            return Integer.MAX_VALUE;
        }

        @Override
        public boolean canHandle(Message message, ProcessingContext context) {
            return ResultParameterResolverFactory.ignoringResultParameters(
                    context, pc -> super.canHandle(message, pc)
            );
        }

        @Override
        public MessageStream<?> handle(Message message,
                                       ProcessingContext context,
                                       @Nullable T target) {
            MessageHandlerInterceptorChain<Message> chain = InterceptorChainParameterResolverFactory.currentInterceptorChain(
                    context);
            if (chain == null) {
                if (logger.isErrorEnabled()) {
                    logger.error("No interceptor chain found in context for exception handler [{}]. "
                                         + "The handler was invoked outside a properly configured interceptor chain.",
                                 signature());
                }
                return MessageStream.failed(new IllegalStateException(
                        "No interceptor chain found in context for exception handler [" + signature() + "]"
                ));
            }
            return chain.proceed(message, context).onErrorContinue(error -> {
                      if (!expectedResultType.isInstance(error)) {
                          return MessageStream.failed(error);
                      }
                      return ResultParameterResolverFactory.callWithResult(
                              error,
                              context,
                              pc -> {
                                  if (super.canHandle(message, pc)) {
                                      //noinspection unchecked
                                      return super.handle(message, pc, target)
                                                  .map(r -> (Entry<Message>) r);
                                  }
                                  return MessageStream.failed(error);
                              }
                      ).cast();
                  });
       }
    }

    private static class InterceptedMessageHandlingMember<T>
            extends WrappedMessageHandlingMember<T>
            implements MessageInterceptingMember<T> {

        private static final Logger logger =
                LoggerFactory.getLogger(InterceptedMessageHandlingMember.class);

        private final boolean shouldInvokeInterceptorChain;

        public InterceptedMessageHandlingMember(MessageHandlingMember<T> original) {
            super(original);
            Method method = original.unwrap(Method.class).orElseThrow(() -> new AxonConfigurationException(
                    "Only methods can be marked as MessageHandlerInterceptor. "
                            + "Violating handler: " + original.signature())
            );
            shouldInvokeInterceptorChain =
                    Arrays.stream(method.getParameters())
                          .noneMatch(p -> p.getType().equals(MessageHandlerInterceptorChain.class));
            if (shouldInvokeInterceptorChain && !Void.TYPE.equals(method.getReturnType())
                    && !CompletableFuture.class.isAssignableFrom(method.getReturnType())) {
                throw new AxonConfigurationException(
                        "A MessageHandlerInterceptor must either return void, a CompletableFuture,"
                                + " or declare a parameter of type InterceptorChain. "
                                + "Violating handler: " + original.signature());
            }
        }

        @Override
        public MessageStream<?> handle(Message message, ProcessingContext context, @Nullable T target) {
            if (!shouldInvokeInterceptorChain) {
                // Surround-interceptor: the method accepts a chain param and calls chain.proceed() itself
                return super.handle(message, context, target);
            }

            // Before-interceptor: void method with no chain param.
            // Run the interceptor method first; if it fails the chain is broken.
            // If it completes normally, lazily proceed the chain via a supplier so that
            // chain.proceed() is only called after the method has finished.
            MessageHandlerInterceptorChain<Message> chain =
                    InterceptorChainParameterResolverFactory.currentInterceptorChain(context);
            if (chain == null) {
                if (logger.isErrorEnabled()) {
                    logger.error("No interceptor chain found in context for before-interceptor [{}]. "
                                         + "The handler was invoked outside a properly configured interceptor chain.",
                                 signature());
                }
                return MessageStream.failed(new IllegalStateException(
                        "No interceptor chain found in context for before-interceptor [" + signature() + "]"
                ));
            }
            return super.handle(message, context, target)
                        .ignoreEntries()
                        .concatWith(() -> chain.proceed(message, context).cast());
        }
    }
}
