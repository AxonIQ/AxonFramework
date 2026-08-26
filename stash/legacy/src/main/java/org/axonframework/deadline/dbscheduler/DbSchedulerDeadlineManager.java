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

package org.axonframework.deadline.dbscheduler;

import static java.lang.String.format;
import static java.util.Objects.isNull;
import static org.axonframework.common.BuilderUtils.assertNonNull;
import static org.axonframework.deadline.dbscheduler.DbSchedulerDeadlineToken.TASK_NAME;
import static org.slf4j.LoggerFactory.getLogger;

import java.util.List;
import java.util.Iterator;
import java.time.Instant;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;


import com.github.kagkarlsson.scheduler.ScheduledExecution;
import com.github.kagkarlsson.scheduler.Scheduler;
import com.github.kagkarlsson.scheduler.SchedulerState;
import com.github.kagkarlsson.scheduler.exceptions.TaskInstanceNotFoundException;
import com.github.kagkarlsson.scheduler.task.Task;
import com.github.kagkarlsson.scheduler.task.TaskDescriptor;
import com.github.kagkarlsson.scheduler.task.TaskInstance;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import org.jspecify.annotations.Nullable;
import org.axonframework.messaging.core.MessageHandlerInterceptor;
import org.axonframework.deadline.DeadlineException;
import org.axonframework.messaging.core.ResultMessage;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.MessageHandlerInterceptorChain;
import org.axonframework.deadline.DeadlineSpans;
import org.axonframework.common.AxonConfigurationException;
import org.axonframework.common.IdentifierFactory;
import org.axonframework.conversion.SerializedObject;
import org.axonframework.conversion.Serializer;
import org.axonframework.deadline.AbstractDeadlineManager;
import org.axonframework.deadline.DeadlineManager;
import org.axonframework.deadline.DeadlineManagerSpanFactory;
import org.axonframework.deadline.DeadlineMessage;
import org.axonframework.deadline.DefaultDeadlineManagerSpanFactory;
import org.axonframework.deadline.GenericDeadlineMessage;
import org.axonframework.deadline.jobrunr.DeadlineDetails;
import org.axonframework.messaging.unitofwork.LegacyDefaultUnitOfWork;
import org.axonframework.messaging.unitofwork.LegacyUnitOfWork;
import org.axonframework.messaging.core.ClassBasedMessageTypeResolver;
import org.axonframework.messaging.core.ExecutionException;
import org.axonframework.messaging.core.MessageTypeResolver;
import org.axonframework.messaging.core.Metadata;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.Scope;
import org.axonframework.messaging.core.ScopeAwareProvider;
import org.axonframework.messaging.core.ScopeDescriptor;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.transaction.NoTransactionManager;
import org.axonframework.messaging.core.unitofwork.transaction.TransactionManager;
import org.axonframework.messaging.tracing.NoOpSpanFactory;
import org.axonframework.messaging.tracing.Span;
import org.axonframework.messaging.tracing.SpanScope;
import org.jobrunr.scheduling.JobScheduler;
import org.slf4j.Logger;

/**
 * Implementation of {@link DeadlineManager} that delegates scheduling and triggering to a db scheduler
 * {@link Scheduler}.
 *
 * @author Gerard Klijs
 * @since 4.8.0
 */
@SuppressWarnings("Duplicates")
public class DbSchedulerDeadlineManager extends AbstractDeadlineManager {

    private static final Logger logger = getLogger(DbSchedulerDeadlineManager.class);
    private static final TaskDescriptor<DbSchedulerBinaryDeadlineDetails> binaryTaskDescriptor =
            TaskDescriptor.of(TASK_NAME, DbSchedulerBinaryDeadlineDetails.class);
    private static final TaskDescriptor<DbSchedulerHumanReadableDeadlineDetails> humanReadableTaskDescriptor =
            TaskDescriptor.of(TASK_NAME, DbSchedulerHumanReadableDeadlineDetails.class);

    private final ScopeAwareProvider scopeAwareProvider;
    private final Scheduler scheduler;
    private final Serializer serializer;
    private final TransactionManager transactionManager;
    private final DeadlineManagerSpanFactory spanFactory;
    private final boolean useBinaryPojo;
    private final AtomicBoolean isShutdown = new AtomicBoolean(false);

    /**
     * Instantiate a Builder to be able to create a {@code DbSchedulerDeadlineManager}.
     * <p>
     * The {@link TransactionManager} is defaulted to a {@link NoTransactionManager}.
     * <p>
     * The {@link DeadlineManagerSpanFactory} is defaulted to a {@link DefaultDeadlineManagerSpanFactory} backed by a
     * {@link NoOpSpanFactory}.
     * <p>
     * The {@code useBinaryPojo} and {@code startScheduler} are defaulted to {@code true}.
     * <p>
     * The {@link Scheduler}, {@link ScopeAwareProvider} and {@link Serializer} are <b>hard requirements</b> and as such
     * should be provided.
     *
     * @return a Builder to be able to create a {@code DbSchedulerDeadlineManager}
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Instantiate a {@code DbSchedulerDeadlineManager} based on the fields contained in the
     * {@link DbSchedulerDeadlineManager.Builder}.
     * <p>
     * Will assert that the {@link ScopeAwareProvider}, {@link Scheduler} and {@link Serializer} are not {@code null},
     * and will throw an {@link AxonConfigurationException} if any of them is {@code null}.
     *
     * @param builder the {@link DbSchedulerDeadlineManager.Builder} used to instantiate a
     *                {@code DbSchedulerDeadlineManager} instance
     */
    protected DbSchedulerDeadlineManager(Builder builder) {
        builder.validate();
        this.scopeAwareProvider = builder.scopeAwareProvider;
        this.scheduler = builder.scheduler;
        this.serializer = builder.serializer;
        this.transactionManager = builder.transactionManager;
        this.spanFactory = builder.spanFactory;
        this.useBinaryPojo = builder.useBinaryPojo;
        this.messageTypeResolver = builder.messageTypeResolver;
    }

    @Override
    public String schedule(Instant triggerDateTime, String deadlineName,
                           @Nullable Object messageOrPayload,
                           ScopeDescriptor deadlineScope) {
        DeadlineMessage deadlineMessage = asDeadlineMessage(deadlineName, messageOrPayload, triggerDateTime);
        String identifier = IdentifierFactory.getInstance().generateIdentifier();
        DbSchedulerDeadlineToken taskInstanceId = new DbSchedulerDeadlineToken(identifier);
        Span span = spanFactory.createScheduleSpan(deadlineName, identifier, deadlineMessage);
        runOnPrepareCommitOrNow(DeadlineSpans.spanned(span, () -> {
            DeadlineMessage message = processDispatchInterceptors(deadlineMessage);
            TaskInstance<?> taskInstance;
            if (useBinaryPojo) {
                taskInstance = binaryTask(deadlineName, deadlineScope, message, taskInstanceId);
            } else {
                taskInstance = humanReadableTask(deadlineName, deadlineScope, message, taskInstanceId);
            }
            scheduler.schedule(taskInstance, triggerDateTime);
            logger.debug("Task with id: [{}] was successfully created.", identifier);
        }));
        return identifier;
    }

    private TaskInstance<?> binaryTask(
            String deadlineName,
            ScopeDescriptor deadlineScope,
            DeadlineMessage interceptedDeadlineMessage,
            DbSchedulerDeadlineToken taskInstanceId
    ) {
        DbSchedulerBinaryDeadlineDetails details = DbSchedulerBinaryDeadlineDetails.serialized(
                deadlineName,
                deadlineScope,
                interceptedDeadlineMessage,
                serializer);
        return binaryTaskDescriptor.instance(taskInstanceId.getId()).data(details).build();
    }

    private TaskInstance<?> humanReadableTask(
            String deadlineName,
            ScopeDescriptor deadlineScope,
            DeadlineMessage interceptedDeadlineMessage,
            DbSchedulerDeadlineToken taskInstanceId
    ) {
        DbSchedulerHumanReadableDeadlineDetails details = DbSchedulerHumanReadableDeadlineDetails.serialized(
                deadlineName,
                deadlineScope,
                interceptedDeadlineMessage,
                serializer);
        return humanReadableTaskDescriptor.instance(taskInstanceId.getId()).data(details).build();
    }

    /**
     * Gives the {@link Task} using {@link DbSchedulerBinaryDeadlineDetails} to execute a deadline via a
     * {@link Scheduler}. To be able to execute the task, this should be added to the task list, used to create the
     * scheduler.
     *
     * @param deadlineManagerSupplier a {@link Supplier} of a {@code DbSchedulerDeadlineManager}. Preferably a method
     *                                involving dependency injection is used. When those are not available the
     *                                {@link DbSchedulerDeadlineManagerSupplier} can be used instead.
     * @return a {@link Task} to execute a deadline
     */
    public static Task<DbSchedulerBinaryDeadlineDetails> binaryTask(
            Supplier<DbSchedulerDeadlineManager> deadlineManagerSupplier) {
        return new Tasks.OneTimeTaskBuilder<>(TASK_NAME, DbSchedulerBinaryDeadlineDetails.class)
                .execute((taskInstance, context) -> {
                    DbSchedulerDeadlineManager deadlineManager = deadlineManagerSupplier.get();
                    if (isNull(deadlineManager)) {
                        throw new DeadlineManagerNotSuppliedException();
                    }
                    deadlineManager.execute(taskInstance.getId(), taskInstance.getData());
                });
    }

    /**
     * Gives the {@link Task} using {@link DbSchedulerHumanReadableDeadlineDetails} to execute a deadline via a
     * {@link Scheduler}. To be able to execute the task, this should be added to the task list, used to create the
     * scheduler.
     *
     * @param deadlineManagerSupplier a {@link Supplier} of a {@code DbSchedulerDeadlineManager}. Preferably a method
     *                                involving dependency injection is used. When those are not available the
     *                                {@link DbSchedulerDeadlineManagerSupplier} can be used instead.
     * @return a {@link Task} to execute a deadline
     */
    public static Task<DbSchedulerHumanReadableDeadlineDetails> humanReadableTask(
            Supplier<DbSchedulerDeadlineManager> deadlineManagerSupplier) {
        return new Tasks.OneTimeTaskBuilder<>(TASK_NAME, DbSchedulerHumanReadableDeadlineDetails.class)
                .execute((taskInstance, context) -> {
                    DbSchedulerDeadlineManager deadlineManager = deadlineManagerSupplier.get();
                    if (isNull(deadlineManager)) {
                        throw new DeadlineManagerNotSuppliedException();
                    }
                    deadlineManager.execute(taskInstance.getId(), taskInstance.getData());
                });
    }

    @Override
    public void cancelSchedule(String deadlineName, String scheduleId) {
        Span span = spanFactory.createCancelScheduleSpan(deadlineName, scheduleId);
        runOnPrepareCommitOrNow(DeadlineSpans.spanned(span, 
                () -> {
                    try {
                        scheduler.cancel(new DbSchedulerDeadlineToken(scheduleId));
                    } catch (TaskInstanceNotFoundException e) {
                        // handle gracefully
                        logger.debug("Attempted to cancel task [{}] which does not exist. The task may have already " +
                                "been canceled or the given schedule identifier is incorrect.", scheduleId);
                    }
                })
        );
    }

    @Override
    public void cancelAll(String deadlineName) {
        Span span = spanFactory.createCancelAllSpan(deadlineName);
        if (useBinaryPojo) {
            runOnPrepareCommitOrNow(DeadlineSpans.spanned(span, 
                    () -> scheduler.fetchScheduledExecutionsForTask(
                            TASK_NAME,
                            DbSchedulerBinaryDeadlineDetails.class,
                            cancelIfBinaryDeadlineMatches(deadlineName)
                    )));
        } else {
            runOnPrepareCommitOrNow(DeadlineSpans.spanned(span, 
                    () -> scheduler.fetchScheduledExecutionsForTask(
                            TASK_NAME,
                            DbSchedulerHumanReadableDeadlineDetails.class,
                            cancelIfHumanReadableDeadlineMatches(deadlineName)
                    )));
        }
    }

    private Consumer<ScheduledExecution<DbSchedulerBinaryDeadlineDetails>> cancelIfBinaryDeadlineMatches(
            String deadlineName
    ) {
        return scheduledExecution -> {
            if (deadlineName.equals(scheduledExecution.getData().getD())) {
                scheduler.cancel(scheduledExecution.getTaskInstance());
            }
        };
    }

    private Consumer<ScheduledExecution<DbSchedulerHumanReadableDeadlineDetails>> cancelIfHumanReadableDeadlineMatches(
            String deadlineName
    ) {
        return scheduledExecution -> {
            if (deadlineName.equals(scheduledExecution.getData().getDeadlineName())) {
                scheduler.cancel(scheduledExecution.getTaskInstance());
            }
        };
    }

    @Override
    public void cancelAllWithinScope(String deadlineName, ScopeDescriptor scope) {
        Span span = spanFactory.createCancelAllWithinScopeSpan(deadlineName, scope);
        if (useBinaryPojo) {
            runOnPrepareCommitOrNow(DeadlineSpans.spanned(span, 
                    () -> {
                        SerializedObject<byte[]> serializedDescriptor = serializer.serialize(scope, byte[].class);
                        scheduler.fetchScheduledExecutionsForTask(
                                TASK_NAME,
                                DbSchedulerBinaryDeadlineDetails.class,
                                cancelIfDeadlineAndScopeMatches(deadlineName, serializedDescriptor.getData()));
                    }));
        } else {
            runOnPrepareCommitOrNow(DeadlineSpans.spanned(span, 
                    () -> {
                        SerializedObject<String> serializedDescriptor = serializer.serialize(scope, String.class);
                        scheduler.fetchScheduledExecutionsForTask(
                                TASK_NAME,
                                DbSchedulerHumanReadableDeadlineDetails.class,
                                cancelIfDeadlineAndScopeMatches(deadlineName, serializedDescriptor.getData()));
                    }));
        }
    }

    private Consumer<ScheduledExecution<DbSchedulerHumanReadableDeadlineDetails>> cancelIfDeadlineAndScopeMatches(
            String deadlineName,
            String scopeDescriptor
    ) {
        return scheduledExecution -> {
            DbSchedulerHumanReadableDeadlineDetails data = scheduledExecution.getData();
            if (deadlineName.equals(data.getDeadlineName()) && scopeDescriptor.equals(data.getScopeDescriptor())) {
                scheduler.cancel(scheduledExecution.getTaskInstance());
            }
        };
    }

    private Consumer<ScheduledExecution<DbSchedulerBinaryDeadlineDetails>> cancelIfDeadlineAndScopeMatches(
            String deadlineName,
            byte[] scopeDescriptor
    ) {
        return scheduledExecution -> {
            DbSchedulerBinaryDeadlineDetails data = scheduledExecution.getData();
            if (deadlineName.equals(data.getD()) && Arrays.equals(scopeDescriptor, data.getS())) {
                scheduler.cancel(scheduledExecution.getTaskInstance());
            }
        };
    }

    /**
     * This function is used by the {@link #binaryTask(Supplier)} to execute the deadline.
     *
     * @param deadlineDetails {@link DbSchedulerBinaryDeadlineDetails} containing the needed details to execute.
     */
    private void execute(String deadlineId, DbSchedulerBinaryDeadlineDetails deadlineDetails) {
        GenericDeadlineMessage deadlineMessage = deadlineDetails.asDeadLineMessage(serializer);
        ScopeDescriptor scopeDescriptor = deadlineDetails.getDeserializedScopeDescriptor(serializer);
        execute(deadlineId, deadlineDetails.getD(), deadlineMessage, scopeDescriptor);
    }

    /**
     * This function is used by the {@link #binaryTask(Supplier)} to execute the deadline.
     *
     * @param deadlineDetails {@link DbSchedulerHumanReadableDeadlineDetails} containing the needed details to execute.
     */
    private void execute(String deadlineId, DbSchedulerHumanReadableDeadlineDetails deadlineDetails) {
        GenericDeadlineMessage deadlineMessage = deadlineDetails.asDeadLineMessage(serializer);
        ScopeDescriptor scopeDescriptor = deadlineDetails.getDeserializedScopeDescriptor(serializer);
        execute(deadlineId, deadlineDetails.getDeadlineName(), deadlineMessage, scopeDescriptor);
    }

    @SuppressWarnings("rawtypes")
    private void execute(String deadlineId, String deadlineName, GenericDeadlineMessage deadlineMessage,
                         ScopeDescriptor scopeDescriptor) {
        Span span = spanFactory.createExecuteSpan(deadlineName, deadlineId, deadlineMessage);
        try (SpanScope ignored = span.start()) {
            LegacyUnitOfWork<GenericDeadlineMessage> unitOfWork = new LegacyDefaultUnitOfWork<>(deadlineMessage);
            unitOfWork.attachTransaction(transactionManager);
            unitOfWork.onRollback(uow -> span.recordException(uow.getExecutionResult().getExceptionResult()));
            // Interceptors are declared against a super type of DeadlineMessage, so each can handle the deadline
            // being triggered here; narrowing them lets the chain below be typed against it.
            @SuppressWarnings("unchecked")
            List<MessageHandlerInterceptor<DeadlineMessage>> narrowed =
                    (List<MessageHandlerInterceptor<DeadlineMessage>>) (List<?>) handlerInterceptors();
            Iterator<MessageHandlerInterceptor<DeadlineMessage>> interceptors = narrowed.iterator();
            MessageHandlerInterceptorChain<DeadlineMessage> chain = new MessageHandlerInterceptorChain<>() {
                @Override
                public MessageStream<?> proceed(DeadlineMessage message, ProcessingContext context) {
                    try {
                        if (interceptors.hasNext()) {
                            return interceptors.next().interceptOnHandle(message, context, this);
                        }
                        executeScheduledDeadline(message, context, scopeDescriptor);
                        return MessageStream.empty();
                    } catch (Exception e) {
                        return MessageStream.failed(e);
                    }
                }
            };

            ResultMessage resultMessage = unitOfWork.executeWithResult(
                    context -> chain.proceed(unitOfWork.getMessage(), context)
            );
            if (resultMessage != null && resultMessage.payload() instanceof Throwable e) {
                span.recordException(e);
                logger.warn("An error occurred while triggering deadline with name [{}].", deadlineName);
                throw new DeadlineException("Failed to process", e);
            }
        }
    }

    private void executeScheduledDeadline(DeadlineMessage deadlineMessage,
                                          ProcessingContext context,
                                          ScopeDescriptor deadlineScope) {
        scopeAwareProvider.provideScopeAwareStream(deadlineScope)
                          .filter(scopeAwareComponent -> scopeAwareComponent.canResolve(deadlineScope))
                          .forEach(scopeAwareComponent -> {
                              try {
                                  scopeAwareComponent.send(deadlineMessage, context, deadlineScope);
                              } catch (Exception e) {
                                  String exceptionMessage = format(
                                          "Failed to send a DeadlineMessage for scope [%s]",
                                          deadlineScope.scopeDescription()
                                  );
                                  throw new ExecutionException(exceptionMessage, e);
                              }
                          });
    }

    /**
     * Will start the {@link Scheduler} depending on its current state and the value of {@code startScheduler},
     */
    public void start() {
        SchedulerState state = scheduler.getSchedulerState();
        if (state.isShuttingDown()) {
            logger.warn("Scheduler is shutting down - will not attempting to start");
            return;
        }
        if (state.isStarted()) {
            logger.info("Scheduler already started - will not attempt to start again");
            return;
        }
        logger.info("Triggering scheduler start");
        scheduler.start();
    }

    @Override
    public void shutdown() {
        if (isShutdown.compareAndSet(false, true)) {
            scheduler.stop();
        }
    }

    /**
     * Builder class to instantiate a {@link DbSchedulerDeadlineManager}.
     * <p>
     * The {@link TransactionManager} is defaulted to a {@link NoTransactionManager}, the
     * {@link DefaultDeadlineManagerSpanFactory} defaults to a {@link DefaultDeadlineManagerSpanFactory} backed by a
     * {@link NoOpSpanFactory}. The {@code useBinaryPojo} default to {@code true}.
     * <p>
     * The {@link JobScheduler}, {@link ScopeAwareProvider} and {@link Serializer} are <b>hard requirements</b> and as
     * such should be provided.
     */
    public static class Builder {

        private Scheduler scheduler;
        private ScopeAwareProvider scopeAwareProvider;
        private Serializer serializer;
        private TransactionManager transactionManager = NoTransactionManager.INSTANCE;
        private DeadlineManagerSpanFactory spanFactory = DefaultDeadlineManagerSpanFactory.builder()
                                                                                          .spanFactory(NoOpSpanFactory.INSTANCE)
                                                                                          .build();
        private MessageTypeResolver messageTypeResolver = new ClassBasedMessageTypeResolver();
        private boolean useBinaryPojo = true;

        /**
         * Sets the {@link Scheduler} used for scheduling and triggering purposes of deadlines. It should have either
         * the {@link #binaryTask(Supplier)} or the {@link #humanReadableTask(Supplier)} from this class as one of its
         * tasks to work. Which one depends on the setting of {@code useBinaryPojo}. When {@code true}, use
         * {@link #binaryTask(Supplier)} else {@link #humanReadableTask(Supplier)}.
         *
         * @param scheduler a {@link Scheduler} used for scheduling and triggering purposes of the deadlines
         * @return the current Builder instance, for fluent interfacing
         */
        public Builder scheduler(Scheduler scheduler) {
            assertNonNull(scheduler, "scheduler may not be null");
            this.scheduler = scheduler;
            return this;
        }

        /**
         * Sets the {@link ScopeAwareProvider} which is capable of providing a stream of
         * {@link Scope} instances for a given {@link ScopeDescriptor}. Used to return the
         * right Scope to trigger a deadline in.
         *
         * @param scopeAwareProvider a {@link ScopeAwareProvider} used to find the right
         *                           {@link Scope} to trigger a deadline in
         * @return the current Builder instance, for fluent interfacing
         */
        public Builder scopeAwareProvider(ScopeAwareProvider scopeAwareProvider) {
            assertNonNull(scopeAwareProvider, "ScopeAwareProvider may not be null");
            this.scopeAwareProvider = scopeAwareProvider;
            return this;
        }

        /**
         * Sets the {@link Serializer} used to de-/serialize the {@code payload},
         * {@link Metadata} and the {@link ScopeDescriptor} into the {@link DeadlineDetails}
         * as well as the whole {@link DeadlineDetails} itself.
         *
         * @param serializer a {@link Serializer} used to de-/serialize the {@code payload},
         *                   {@link Metadata} and the {@link ScopeDescriptor} into the
         *                   {@link DeadlineDetails}, as well as the whole {@link DeadlineDetails} itself.
         * @return the current Builder instance, for fluent interfacing
         */
        public Builder serializer(Serializer serializer) {
            assertNonNull(serializer, "Serializer may not be null");
            this.serializer = serializer;
            return this;
        }

        /**
         * Sets the {@link TransactionManager} used to build transactions and ties them to deadline. Defaults to a
         * {@link NoTransactionManager}.
         *
         * @param transactionManager a {@link TransactionManager} used to build transactions and ties them to deadline
         * @return the current Builder instance, for fluent interfacing
         */
        public Builder transactionManager(TransactionManager transactionManager) {
            assertNonNull(transactionManager, "TransactionManager may not be null");
            this.transactionManager = transactionManager;
            return this;
        }

        /**
         * Sets the {@link DeadlineManagerSpanFactory} implementation to use for providing tracing capabilities.
         * Defaults to a {@link DefaultDeadlineManagerSpanFactory} backed by a {@link NoOpSpanFactory} by default, which
         * provides no tracing capabilities.
         *
         * @param spanFactory The {@link DeadlineManagerSpanFactory} implementation
         * @return The current Builder instance, for fluent interfacing.
         */
        public Builder spanFactory(DeadlineManagerSpanFactory spanFactory) {
            assertNonNull(spanFactory, "SpanFactory may not be null");
            this.spanFactory = spanFactory;
            return this;
        }

        /**
         * Sets whether to use a pojo optimized for size, {@link DbSchedulerBinaryDeadlineDetails}, compared to a
         * pojo optimized for readability, {@link DbSchedulerHumanReadableDeadlineDetails}.
         *
         * @param useBinaryPojo a {@code boolean} to determine whether to use a binary format.
         * @return the current Builder instance, for fluent interfacing
         */
        public Builder useBinaryPojo(boolean useBinaryPojo) {
            this.useBinaryPojo = useBinaryPojo;
            return this;
        }

        /**
         * Sets the {@link MessageTypeResolver} used to resolve the {@link QualifiedName} when scheduling
         * {@link DeadlineMessage DeadlineMessages}. If not set, a {@link ClassBasedMessageTypeResolver} is used by
         * default.
         *
         * @param messageTypeResolver The {@link MessageTypeResolver} used to provide the {@link QualifiedName} for
         *                            {@link DeadlineMessage DeadlineMessages}.
         * @return The current Builder instance, for fluent interfacing.
         */
        public Builder messageNameResolver(MessageTypeResolver messageTypeResolver) {
            assertNonNull(messageTypeResolver, "MessageNameResolver may not be null");
            this.messageTypeResolver = messageTypeResolver;
            return this;
        }

        /**
         * Initializes a {@link DbSchedulerDeadlineManager} as specified through this Builder.
         *
         * @return a {@link DbSchedulerDeadlineManager} as specified through this Builder
         */
        public DbSchedulerDeadlineManager build() {
            return new DbSchedulerDeadlineManager(this);
        }

        /**
         * Validates whether the fields contained in this Builder are set accordingly.
         *
         * @throws AxonConfigurationException if one field is asserted to be incorrect according to the Builder's
         *                                    specifications
         */
        protected void validate() throws AxonConfigurationException {
            assertNonNull(scopeAwareProvider, "The ScopeAwareProvider is a hard requirement and should be provided.");
            assertNonNull(scheduler, "The Scheduler is a hard requirement and should be provided.");
            assertNonNull(serializer, "The Serializer is a hard requirement and should be provided.");
        }
    }
}