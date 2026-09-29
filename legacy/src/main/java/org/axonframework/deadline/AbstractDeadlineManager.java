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

package org.axonframework.deadline;

import org.axonframework.common.FutureUtils;
import org.axonframework.common.ObjectUtils;
import org.axonframework.common.Registration;
import org.axonframework.messaging.core.ClassBasedMessageTypeResolver;
import org.axonframework.messaging.core.Context;
import org.axonframework.messaging.core.ContextAwareScope;
import org.axonframework.messaging.core.DefaultMessageDispatchInterceptorChain;
import org.axonframework.messaging.core.GenericMessage;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageDispatchInterceptor;
import org.axonframework.messaging.core.MessageHandlerInterceptor;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.MessageTypeResolver;
import org.axonframework.messaging.core.interception.CorrelationDataInterceptor;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.ProcessingLifecycle;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Abstract implementation of the {@link DeadlineManager} to be implemented by concrete solutions for the
 * DeadlineManager. Provides functionality to perform a call to the DeadlineManager when the {@link ProcessingContext}
 * of the current invocation prepares its commit. This {@link #runOnPrepareCommitOrNow(Runnable)} functionality is
 * required, as the DeadlineManager schedules a Message which needs to happen on order with the other messages
 * published throughout the system.
 * <p>
 * Axon Framework 4 found that moment through the ambient unit of work. Axon Framework 5 has none, so a call is
 * deferred when it is made while a {@link ContextAwareScope} is current, which is the case while a Saga handler
 * method runs. A call made anywhere else runs immediately.
 *
 * @author Steven van Beelen
 * @since 3.3
 */
public abstract class AbstractDeadlineManager implements DeadlineManager {

    /**
     * The {@link ProcessingLifecycle.Phase phase} in which calls deferred by {@link #runOnPrepareCommitOrNow(Runnable)}
     * run, ordered after {@link ProcessingLifecycle.DefaultPhases#PREPARE_COMMIT PREPARE_COMMIT} and the Saga write
     * ({@code AnnotatedSagaRepository.WRITE_SAGA}), and before {@link ProcessingLifecycle.DefaultPhases#COMMIT COMMIT}.
     * <p>
     * A {@link ProcessingContext} rejects a registration for the phase it is already running, and a Saga is invoked
     * from within {@code PREPARE_COMMIT} when a subscribing event processor is fed by a
     * {@link org.axonframework.messaging.eventhandling.SimpleEventBus}. Registering for {@code PREPARE_COMMIT} itself
     * would therefore fail exactly there, so the calls are ordered into the gap above it instead. Following the Saga
     * write keeps the Axon Framework 4 order, where the Saga write was registered for prepare-commit when the Saga was
     * loaded, before its handler made any deadline call.
     */
    public static final ProcessingLifecycle.Phase RUN_DEADLINE_CALLS =
            () -> ProcessingLifecycle.DefaultPhases.PREPARE_COMMIT.order() + 7_500;

    /**
     * Shared by every deadline manager on purpose: one queue per {@link ProcessingContext} keeps the calls of all
     * managers in the order they were made, as the single prepare-commit handler list of an Axon Framework 4 unit of
     * work did.
     */
    private static final Context.ResourceKey<Queue<Runnable>> DEFERRED_CALLS =
            Context.ResourceKey.withLabel("deferredDeadlineCalls");

    private final List<MessageDispatchInterceptor<? super DeadlineMessage>> dispatchInterceptors =
            new CopyOnWriteArrayList<>();
    private final List<MessageHandlerInterceptor<? super DeadlineMessage>> handlerInterceptors =
            new CopyOnWriteArrayList<>();
    protected MessageTypeResolver messageTypeResolver = new ClassBasedMessageTypeResolver();

    /**
     * Run a given {@code deadlineCall} immediately, or defer it to the {@link #RUN_DEADLINE_CALLS} phase of the
     * {@link ProcessingContext} carried by the current {@link ContextAwareScope}, if one is active. This is required as
     * the DeadlineManager schedules messages which we want to happen in order with other messages being handled.
     * <p>
     * Deferred calls run in the order they were made, also across Sagas and deadline managers sharing the same
     * {@link ProcessingContext}, as they did in the prepare-commit phase of an Axon Framework 4 unit of work. A failing
     * call fails the commit, and the calls made after it do not run. None of them run when the context rolls back
     * before reaching that phase.
     *
     * @param deadlineCall a {@link Runnable} to be executed now, or when the {@link ProcessingContext} of the current
     *                     scope prepares its commit
     */
    protected void runOnPrepareCommitOrNow(Runnable deadlineCall) {
        Optional<ProcessingContext> context = ContextAwareScope.currentProcessingContext();
        if (context.isPresent()) {
            deferredCalls(context.get()).add(deadlineCall);
        } else {
            deadlineCall.run();
        }
    }

    /**
     * Returns the queue of calls deferred to the given {@code context} by any deadline manager, creating it and
     * registering its {@link #RUN_DEADLINE_CALLS} action on first use.
     * <p>
     * One phase action drains the whole queue so that the calls keep their order: the actions registered for a single
     * phase may run concurrently. The action is registered before the queue is stored on the context, so a call made
     * once the phase started fails on that registration, as every such call did in Axon Framework 4, and never leaves
     * a queue behind that later calls would join without anything draining it. The queue is removed before it is
     * drained for the same reason.
     */
    private static Queue<Runnable> deferredCalls(ProcessingContext context) {
        Queue<Runnable> existing = context.getResource(DEFERRED_CALLS);
        if (existing != null) {
            return existing;
        }
        Queue<Runnable> created = new ConcurrentLinkedQueue<>();
        context.runOn(RUN_DEADLINE_CALLS, phaseContext -> {
            phaseContext.removeResource(DEFERRED_CALLS, created);
            created.forEach(Runnable::run);
        });
        Queue<Runnable> raced = context.putResourceIfAbsent(DEFERRED_CALLS, created);
        return raced != null ? raced : created;
    }

    /**
     * Registers the given {@code dispatchInterceptor}, applied to every deadline message this manager schedules, see
     * {@link #processDispatchInterceptors(DeadlineMessage)}.
     *
     * @param dispatchInterceptor the interceptor to apply to deadline messages before they are scheduled
     * @return a {@link Registration} to cancel to unregister the interceptor again
     */
    public Registration registerDispatchInterceptor(
            MessageDispatchInterceptor<? super DeadlineMessage> dispatchInterceptor) {
        dispatchInterceptors.add(dispatchInterceptor);
        return () -> dispatchInterceptors.remove(dispatchInterceptor);
    }

    /**
     * Registers the given {@code handlerInterceptor}, to be applied by implementations to every deadline message they
     * hand to its handler, see {@link #handlerInterceptors()}.
     *
     * @param handlerInterceptor the interceptor to apply to deadline messages when they are handled
     * @return a {@link Registration} to cancel to unregister the interceptor again
     */
    public Registration registerHandlerInterceptor(
            MessageHandlerInterceptor<? super DeadlineMessage> handlerInterceptor) {
        handlerInterceptors.add(handlerInterceptor);
        return () -> handlerInterceptors.remove(handlerInterceptor);
    }

    /**
     * Provides a list of registered dispatch interception. Do note that this list is not modifiable, and that changes
     * in the internal structure for dispatch interception will be reflected in this list.
     *
     * @return a list of dispatch interception
     */
    protected List<MessageDispatchInterceptor<? super DeadlineMessage>> dispatchInterceptors() {
        return Collections.unmodifiableList(dispatchInterceptors);
    }

    /**
     * Provides a list of registered handler interception. Do note that this list is not modifiable, and that changes in
     * the internal structure for handler interception will be reflected in this list.
     *
     * @return a list of handler interception
     */
    protected List<MessageHandlerInterceptor<? super DeadlineMessage>> handlerInterceptors() {
        return Collections.unmodifiableList(handlerInterceptors);
    }

    /**
     * Applies registered {@link MessageDispatchInterceptor}s to the given {@code message}.
     * <p>
     * A failing interceptor fails this call with the interceptor's own exception. An interceptor that ends the chain
     * without a message results in {@code null}, the counterpart of an Axon Framework 4 interceptor returning
     * {@code null}.
     *
     * @param message the deadline message to be intercepted
     * @return intercepted message, or {@code null} if an interceptor ended the chain without one
     */
    protected @Nullable DeadlineMessage processDispatchInterceptors(DeadlineMessage message) {
        return FutureUtils.joinAndUnwrap(
                new DefaultMessageDispatchInterceptorChain<>(dispatchInterceptors())
                        .proceed(message, null)
                        .first()
                        .<DeadlineMessage>cast()
                        .asCompletableFuture()
                        .thenApply(entry -> entry == null ? null : entry.message())
        );
    }

    /**
     * Returns the given {@code deadlineName} and {@code messageOrPayload} as a DeadlineMessage which expires at the
     * given {@code expiryTime}. If the {@code messageOrPayload} parameter is of type {@link Message}, a new
     * {@code DeadlineMessage} instance will be created using the payload and meta data of the given message. Otherwise,
     * the given {@code messageOrPayload} is wrapped into a {@code GenericDeadlineMessage} as its payload.
     * <p>
     * A message wrapping a payload carries the correlation data of the {@link ProcessingContext} of the current
     * {@link ContextAwareScope}, if any, as an Axon Framework 4 message carried the correlation data of the current
     * unit of work. A given {@link Message} is taken over as it is, as it was in Axon Framework 4.
     *
     * @param deadlineName     the name for this {@link DeadlineMessage}
     * @param messageOrPayload a {@link Message} or payload to wrap as a DeadlineMessage
     * @param expiryTime       the timestamp at which the deadline expires
     * @return a DeadlineMessage using the {@code deadlineName} as its deadline qualifiedName and containing the given
     * {@code messageOrPayload} as the payload
     */
    protected DeadlineMessage asDeadlineMessage(String deadlineName,
                                                @Nullable Object messageOrPayload,
                                                Instant expiryTime) {
        if (messageOrPayload instanceof Message) {
            return new GenericDeadlineMessage(deadlineName,
                                              (Message) messageOrPayload,
                                              () -> expiryTime);
        }
        MessageType type = messageTypeResolver.resolveOrThrow(ObjectUtils.nullSafeTypeOf(messageOrPayload));
        return new GenericDeadlineMessage(
                deadlineName, new GenericMessage(type, messageOrPayload, currentCorrelationData()), () -> expiryTime
        );
    }

    private static Map<String, String> currentCorrelationData() {
        return ContextAwareScope.currentProcessingContext()
                                .map(context -> context.getResource(CorrelationDataInterceptor.CORRELATION_DATA))
                                .orElse(Map.of());
    }
}
