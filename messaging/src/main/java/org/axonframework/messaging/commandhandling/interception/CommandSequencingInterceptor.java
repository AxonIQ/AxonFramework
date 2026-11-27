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

package org.axonframework.messaging.commandhandling.interception;

import org.axonframework.common.FutureUtils;
import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.core.DelayedMessageStream;
import org.axonframework.messaging.core.MessageHandlerInterceptor;
import org.axonframework.messaging.core.MessageHandlerInterceptorChain;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.correlation.MessageOriginProvider;
import org.axonframework.messaging.core.sequencing.SequencingPolicy;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Deque;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentMap;

/**
 * A {@link MessageHandlerInterceptor} implementation to sequence command execution.
 * <p>
 * Commands are sequenced based on a {@link SequencingPolicy} that specifies how to determine the sequence identifier
 * from the {@link CommandMessage} that is being intercepted. Commands with the same sequence identifier will be
 * executed sequentially, meaning that a command will wait for the previous command with the same identifier to complete
 * before starting execution. Commands with different sequence identifiers can be executed concurrently.
 * <p>
 * Commands for which the policy returns {@code Optional.empty()} as sequence identifier will proceed without any
 * sequencing constraints.
 *
 * @param <M> the Message type this interceptor can process
 * @author Jakob Hatzl
 * @since 5.0.3
 */
public class CommandSequencingInterceptor<M extends CommandMessage> implements MessageHandlerInterceptor<M> {

    private static final Logger logger = LoggerFactory.getLogger(CommandSequencingInterceptor.class);

    private final SequencingPolicy<? super CommandMessage> sequencingPolicy;
    private final ConcurrentMap<Object, CompletableFuture<Void>> inProgress;
    private final ConcurrentMap<Object, Deque<ActiveExecution>> lockStacks;

    /**
     * Construct a {@code CommandSequencingInterceptor} that sequences command execution based on the supplied
     * {@link SequencingPolicy}.
     *
     * @param sequencingPolicy the {@link SequencingPolicy} to apply for retrieving the sequence identifier from the
     *                         {@link CommandMessage}.
     */
    public CommandSequencingInterceptor(SequencingPolicy<? super CommandMessage> sequencingPolicy) {
        Objects.requireNonNull(sequencingPolicy, "The sequencingPolicy must not be null");
        this.sequencingPolicy = sequencingPolicy;
        this.inProgress = new ConcurrentHashMap<>();
        this.lockStacks = new ConcurrentHashMap<>();
    }

    @Override
    public MessageStream<?> interceptOnHandle(M message,
                                              ProcessingContext context,
                                              MessageHandlerInterceptorChain<M> interceptorChain) {
        Object sequenceIdentifier = sequencingPolicy.sequenceIdentifierFor(message, context).orElse(null);
        if (sequenceIdentifier != null) {
            Deque<ActiveExecution> stack = lockStacks.get(sequenceIdentifier);
            if (stack != null) {
                ActiveExecution currentHolder = stack.peek();
                if (currentHolder != null && currentHolder.shouldForwardLock(message)) {
                    logger.debug(
                            "Forwarding lock for [{}] in {} to nested command [{}].",
                            sequenceIdentifier,
                            context,
                            message.identifier()
                    );
                    ActiveExecution childExecution = new ActiveExecution(
                            message.identifier(),
                            message.metadata().get(MessageOriginProvider.DEFAULT_CORRELATION_KEY)
                    );
                    stack.push(childExecution);

                    context.doFinally(ctx -> {
                        logger.debug(
                                "Nested command [{}] completed. Returning lock to parent in {}.",
                                childExecution.commandIdentifier(),
                                sequenceIdentifier
                        );
                        stack.remove(childExecution);
                    });

                    return interceptorChain.proceed(message, context);
                }
            }

            // await turn to sequence command execution
            logger.debug("Sequencing command execution for [{}] in {}", sequenceIdentifier, context);

            // Create done marker for this command
            CompletableFuture<Void> currentLock = new CompletableFuture<>();
            CompletableFuture<Void> previousLock = inProgress.put(sequenceIdentifier, currentLock);

            // Automatic cleanup when current task completes
            currentLock.whenComplete((r, e) -> inProgress.remove(sequenceIdentifier, currentLock));

            // make the previous lock an empty future to complete immediately if none is present
            if (previousLock == null) {
                logger.debug("No previous command execution for [{}] in {}. Processing immediately.",
                             sequenceIdentifier,
                             context);
                previousLock = FutureUtils.emptyCompletedFuture();
            }

            return DelayedMessageStream.create(
                    previousLock.handle((r, e) -> {
                        ActiveExecution rootExecution = new ActiveExecution(
                                message.identifier(),
                                message.metadata().get(MessageOriginProvider.DEFAULT_CORRELATION_KEY)
                        );
                        Deque<ActiveExecution> newStack =
                                lockStacks.computeIfAbsent(sequenceIdentifier, k -> new ConcurrentLinkedDeque<>());
                        newStack.push(rootExecution);

                        context.doFinally(ctx -> {
                            logger.debug(
                                    "Processing command for [{}] completed in {}. Passing lock to next command.",
                                    sequenceIdentifier,
                                    ctx
                            );
                            newStack.remove(rootExecution);
                            if (newStack.isEmpty()) {
                                lockStacks.remove(sequenceIdentifier, newStack);
                            }
                            currentLock.complete(null);
                        });
                        logger.debug(
                                "Proceeding command execution for [{}] in {}.",
                                sequenceIdentifier,
                                context);
                        return interceptorChain.proceed(message, context);
                    })
            );
        } else {
            logger.debug("Missing sequence identifier, skipping command execution sequencing in {}", context);
            return interceptorChain.proceed(message, context);
        }
    }

    private record ActiveExecution(
            String commandIdentifier,
            @Nullable String correlationId
    ) {
        boolean shouldForwardLock(CommandMessage message) {
            return isAncestorOf(message);
        }

        boolean isAncestorOf(CommandMessage message) {
            String msgCorrelationId = message.metadata().get(MessageOriginProvider.DEFAULT_CORRELATION_KEY);
            String msgCausationId = message.metadata().get(MessageOriginProvider.DEFAULT_CAUSATION_KEY);
            if (msgCorrelationId != null) {
                if (msgCorrelationId.equals(commandIdentifier) || msgCorrelationId.equals(correlationId)) {
                    return true;
                }
            }
            if (msgCausationId != null && msgCausationId.equals(commandIdentifier)) {
                return true;
            }
            return false;
        }
    }
}
