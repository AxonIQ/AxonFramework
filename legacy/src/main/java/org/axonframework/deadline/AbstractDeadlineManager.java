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
import org.axonframework.messaging.core.CurrentScope;
import org.axonframework.messaging.core.DefaultMessageDispatchInterceptorChain;
import org.axonframework.messaging.core.GenericMessage;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageDispatchInterceptor;
import org.axonframework.messaging.core.MessageHandlerInterceptor;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.MessageTypeResolver;
import org.axonframework.messaging.core.ScopeDescriptor;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Abstract implementation of the {@link DeadlineManager} to be implemented by concrete solutions for the
 * DeadlineManager.
 * <p>
 * Concrete subclasses implement {@link #doSchedule(DeadlineMessage, ScopeDescriptor, ProcessingContext)},
 * {@link #doCancelSchedule(String, String, ProcessingContext)}, {@link #doCancelAll(String, ProcessingContext)} and
 * {@link #doCancelAllWithinScope(String, ScopeDescriptor, ProcessingContext)} instead of the {@link DeadlineManager}
 * methods directly: this class implements the public interface once, on top of those four primitives, and reuses
 * that same implementation for both a call made without an active {@link ProcessingContext} (the deprecated,
 * field/constructor-injected usage) and a call made through a specific {@link ProcessingContext}, via
 * {@link #withContext(ProcessingContext)}.
 * <p>
 * A call made through {@link #withContext(ProcessingContext)} defers the actual scheduling/cancellation call until
 * that context reaches its prepare-commit phase, so a call made while the surrounding transaction later rolls back
 * never reaches the backing store. A call made without one runs immediately, since there is nothing to defer to.
 *
 * @author Steven van Beelen
 * @since 3.3
 */
public abstract class AbstractDeadlineManager implements DeadlineManager {

    private final List<MessageDispatchInterceptor<? super DeadlineMessage>> dispatchInterceptors = new CopyOnWriteArrayList<>();
    private final List<MessageHandlerInterceptor<? super DeadlineMessage>> handlerInterceptors = new CopyOnWriteArrayList<>();
    protected MessageTypeResolver messageTypeResolver = new ClassBasedMessageTypeResolver();

    @Override
    public String schedule(Instant triggerDateTime,
                           String deadlineName,
                           @Nullable Object messageOrPayload,
                           ScopeDescriptor deadlineScope) {
        DeadlineMessage message = FutureUtils.joinAndUnwrap(
                processDispatchInterceptors(asDeadlineMessage(deadlineName, messageOrPayload, triggerDateTime), null)
        );
        return doSchedule(message, deadlineScope, null);
    }

    @Override
    public void cancelSchedule(String deadlineName, String scheduleId) {
        doCancelSchedule(deadlineName, scheduleId, null);
    }

    @Override
    public void cancelAll(String deadlineName) {
        doCancelAll(deadlineName, null);
    }

    @Override
    public void cancelAllWithinScope(String deadlineName, ScopeDescriptor scope) {
        doCancelAllWithinScope(deadlineName, scope, null);
    }

    /**
     * Returns a {@link DeadlineManager} bound to the given {@code context}. Every {@code schedule}/{@code cancel}
     * call made through the returned instance defers to {@code context}'s prepare-commit phase, and a call that
     * omits an explicit {@link ScopeDescriptor} resolves the scope registered for {@code context} (see
     * {@link CurrentScope}) instead of {@link org.axonframework.messaging.core.NoScopeDescriptor#INSTANCE}.
     * <p>
     * This is the instance a {@link DeadlineManager}-typed handler-method parameter resolves to; framework code
     * outside this module does not normally call this method directly.
     *
     * @param context the {@link ProcessingContext} to bind the returned {@link DeadlineManager} to
     * @return a {@link DeadlineManager} bound to {@code context}
     */
    public DeadlineManager withContext(ProcessingContext context) {
        return new ContextBoundDeadlineManager(context);
    }

    /**
     * Schedules the given {@code deadlineMessage} within the given {@code scope}, deferring the actual call to
     * {@code context}'s prepare-commit phase via {@link #runOnPrepareCommitOrNow(ProcessingContext, Runnable)} when
     * {@code context} is not {@code null}.
     *
     * @param deadlineMessage the deadline to schedule, already run through the registered dispatch interceptors
     * @param scope           the scope the deadline is scheduled within
     * @param context         the {@link ProcessingContext} to defer the actual scheduling call to, or {@code null}
     *                        when there is none to defer to
     * @return the {@code scheduleId} to use when cancelling the schedule
     */
    protected abstract String doSchedule(DeadlineMessage deadlineMessage,
                                         ScopeDescriptor scope,
                                         @Nullable ProcessingContext context);

    /**
     * Cancels the deadline corresponding to the given {@code deadlineName} / {@code scheduleId} combination, deferring
     * the actual call to {@code context}'s prepare-commit phase via
     * {@link #runOnPrepareCommitOrNow(ProcessingContext, Runnable)} when {@code context} is not {@code null}.
     *
     * @param deadlineName the name of the deadline to cancel
     * @param scheduleId   the scheduled deadline to cancel
     * @param context      the {@link ProcessingContext} to defer the actual cancellation call to, or {@code null}
     *                     when there is none to defer to
     */
    protected abstract void doCancelSchedule(String deadlineName, String scheduleId, @Nullable ProcessingContext context);

    /**
     * Cancels all deadlines corresponding to the given {@code deadlineName}, deferring the actual call to
     * {@code context}'s prepare-commit phase via {@link #runOnPrepareCommitOrNow(ProcessingContext, Runnable)} when
     * {@code context} is not {@code null}.
     *
     * @param deadlineName the name of the deadlines to cancel
     * @param context      the {@link ProcessingContext} to defer the actual cancellation call to, or {@code null}
     *                     when there is none to defer to
     */
    protected abstract void doCancelAll(String deadlineName, @Nullable ProcessingContext context);

    /**
     * Cancels all deadlines corresponding to the given {@code deadlineName} and {@code scope}, deferring the actual
     * call to {@code context}'s prepare-commit phase via
     * {@link #runOnPrepareCommitOrNow(ProcessingContext, Runnable)} when {@code context} is not {@code null}.
     *
     * @param deadlineName the name of the deadlines to cancel
     * @param scope        the scope the deadlines were scheduled within
     * @param context      the {@link ProcessingContext} to defer the actual cancellation call to, or {@code null}
     *                     when there is none to defer to
     */
    protected abstract void doCancelAllWithinScope(String deadlineName,
                                                   ScopeDescriptor scope,
                                                   @Nullable ProcessingContext context);

    /**
     * Run a given {@code deadlineCall} immediately, or defer it to the given {@code context}'s prepare-commit phase
     * if {@code context} is not {@code null}. This is required as the DeadlineManager schedules/cancels work which we
     * want to happen in order with other messages being handled within that same context.
     *
     * @param context      the {@link ProcessingContext} to defer {@code deadlineCall} to, or {@code null} to run it
     *                     immediately
     * @param deadlineCall a {@link Runnable} to be executed now, or on {@code context}'s prepare-commit phase if
     *                     {@code context} is not {@code null}
     */
    protected void runOnPrepareCommitOrNow(@Nullable ProcessingContext context, Runnable deadlineCall) {
        if (context != null) {
            context.runOnPrepareCommit(ctx -> deadlineCall.run());
        } else {
            deadlineCall.run();
        }
    }

    public Registration registerDispatchInterceptor(
            MessageDispatchInterceptor<? super DeadlineMessage> dispatchInterceptor) {
        dispatchInterceptors.add(dispatchInterceptor);
        return () -> dispatchInterceptors.remove(dispatchInterceptor);
    }

    public Registration registerHandlerInterceptor(
            MessageHandlerInterceptor<DeadlineMessage> handlerInterceptor) {
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
     *
     * @param message the deadline message to be intercepted
     * @param context the {@link ProcessingContext} the interceptors run in, or {@code null} when there is none
     * @return a future resolving to the intercepted message
     */
    protected CompletableFuture<DeadlineMessage> processDispatchInterceptors(DeadlineMessage message,
                                                                             @Nullable ProcessingContext context) {
        return new DefaultMessageDispatchInterceptorChain<>(dispatchInterceptors())
                .proceed(message, context)
                .first()
                .<DeadlineMessage>cast()
                .asCompletableFuture()
                .thenApply(entry -> {
                    if (entry == null) {
                        throw new IllegalStateException(
                                "A dispatch interceptor swallowed the deadline message for deadline '"
                                        + message.getDeadlineName()
                                        + "'. A deadline cannot be scheduled without a message."
                        );
                    }
                    return entry.message();
                });
    }

    /**
     * Returns the given {@code deadlineName} and {@code messageOrPayload} as a DeadlineMessage which expires at the
     * given {@code expiryTime}. If the {@code messageOrPayload} parameter is of type {@link Message}, a new
     * {@code DeadlineMessage} instance will be created using the payload and meta data of the given message. Otherwise,
     * the given {@code messageOrPayload} is wrapped into a {@code GenericDeadlineMessage} as its payload.
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
                deadlineName, new GenericMessage(type, messageOrPayload), () -> expiryTime
        );
    }

    /**
     * A {@link DeadlineManager} bound to a specific {@link ProcessingContext}, returned by
     * {@link #withContext(ProcessingContext)}. Delegates every call to the enclosing {@link AbstractDeadlineManager}'s
     * {@code do...} primitives, always passing its bound context through instead of {@code null}, and resolves an
     * omitted scope via {@link CurrentScope#describeCurrentScope(ProcessingContext)} instead of
     * {@link org.axonframework.messaging.core.NoScopeDescriptor#INSTANCE}.
     */
    private final class ContextBoundDeadlineManager implements DeadlineManager {

        private final ProcessingContext context;

        private ContextBoundDeadlineManager(ProcessingContext context) {
            this.context = context;
        }

        @Override
        public String schedule(Instant triggerDateTime,
                               String deadlineName,
                               @Nullable Object messageOrPayload,
                               ScopeDescriptor deadlineScope) {
            DeadlineMessage message = FutureUtils.joinAndUnwrap(processDispatchInterceptors(
                    asDeadlineMessage(deadlineName, messageOrPayload, triggerDateTime), context
            ));
            return doSchedule(message, deadlineScope, context);
        }

        @Override
        public String schedule(Instant triggerDateTime, String deadlineName, @Nullable Object messageOrPayload) {
            return schedule(triggerDateTime, deadlineName, messageOrPayload, CurrentScope.describeCurrentScope(context));
        }

        @Override
        public String schedule(java.time.Duration triggerDuration, String deadlineName,
                               @Nullable Object messageOrPayload) {
            return schedule(triggerDuration, deadlineName, messageOrPayload, CurrentScope.describeCurrentScope(context));
        }

        @Override
        public void cancelSchedule(String deadlineName, String scheduleId) {
            doCancelSchedule(deadlineName, scheduleId, context);
        }

        @Override
        public void cancelAll(String deadlineName) {
            doCancelAll(deadlineName, context);
        }

        @Override
        public void cancelAllWithinScope(String deadlineName) {
            cancelAllWithinScope(deadlineName, CurrentScope.describeCurrentScope(context));
        }

        @Override
        public void cancelAllWithinScope(String deadlineName, ScopeDescriptor scope) {
            doCancelAllWithinScope(deadlineName, scope, context);
        }
    }
}
