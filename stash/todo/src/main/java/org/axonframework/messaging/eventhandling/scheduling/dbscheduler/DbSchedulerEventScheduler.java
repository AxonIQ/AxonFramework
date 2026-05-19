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

package org.axonframework.messaging.eventhandling.scheduling.dbscheduler;

import com.github.kagkarlsson.scheduler.Scheduler;
import com.github.kagkarlsson.scheduler.SchedulerState;
import com.github.kagkarlsson.scheduler.task.Task;
import com.github.kagkarlsson.scheduler.task.TaskDescriptor;
import com.github.kagkarlsson.scheduler.task.TaskInstance;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import org.axonframework.common.AxonConfigurationException;
import org.axonframework.common.ClockUtils;
import org.axonframework.common.IdentifierFactory;
import org.axonframework.messaging.core.unitofwork.transaction.NoTransactionManager;
import org.axonframework.messaging.core.unitofwork.transaction.TransactionManager;
import org.axonframework.messaging.eventhandling.EventBus;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.GenericEventMessage;
import org.axonframework.messaging.eventhandling.scheduling.EventScheduler;
import org.axonframework.messaging.eventhandling.scheduling.ScheduleToken;
import org.axonframework.messaging.eventhandling.scheduling.SchedulingException;
import org.axonframework.messaging.core.ClassBasedMessageTypeResolver;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageTypeResolver;
import org.axonframework.messaging.core.Metadata;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.unitofwork.LegacyDefaultUnitOfWork;
import org.axonframework.messaging.unitofwork.LegacyUnitOfWork;
import org.axonframework.conversion.SerializedObject;
import org.axonframework.conversion.Serializer;
import org.axonframework.conversion.SimpleSerializedObject;
import org.slf4j.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static java.util.Objects.isNull;
import static org.axonframework.common.BuilderUtils.assertNonNull;
import static org.axonframework.messaging.eventhandling.scheduling.dbscheduler.DbSchedulerScheduleToken.TASK_NAME;
import static org.slf4j.LoggerFactory.getLogger;

/**
 * EventScheduler implementation that delegates scheduling and triggering to a db-scheduler Scheduler.
 *
 * @author Gerard Klijs
 * @since 4.8.0
 */
@SuppressWarnings("Duplicates")
public class DbSchedulerEventScheduler implements EventScheduler {

    private static final Logger logger = getLogger(DbSchedulerEventScheduler.class);
    private static final TaskDescriptor<DbSchedulerHumanReadableEventData> humanReadableTaskDescriptor =
            TaskDescriptor.of(TASK_NAME, DbSchedulerHumanReadableEventData.class);
    private static final TaskDescriptor<DbSchedulerBinaryEventData> binaryTaskDescriptor =
            TaskDescriptor.of(TASK_NAME, DbSchedulerBinaryEventData.class);
    private final Scheduler scheduler;
    private final Serializer serializer;
    private final TransactionManager transactionManager;
    private final EventBus eventBus;
    private final boolean useBinaryPojo;
    private final boolean startScheduler;
    private final boolean stopScheduler;
    private final MessageTypeResolver messageTypeResolver;
    private final AtomicBoolean isShutdown = new AtomicBoolean(false);

    /**
     * Instantiate a  based on the fields contained in the
     * {@link DbSchedulerEventScheduler.Builder}.
     * <p>
     * Will assert that the {@link Scheduler}, {@link Serializer} and {@link EventBus} are not {@code null}, and will
     * throw an {@link AxonConfigurationException} if any of them is {@code null}.
     *
     * @param builder the {@link Builder} used to instantiate a  instance
     */
    protected DbSchedulerEventScheduler(Builder builder) {
        builder.validate();
        scheduler = builder.scheduler;
        serializer = builder.serializer;
        transactionManager = builder.transactionManager;
        eventBus = builder.eventBus;
        useBinaryPojo = builder.useBinaryPojo;
        startScheduler = builder.startScheduler;
        stopScheduler = builder.stopScheduler;
        messageTypeResolver = builder.messageTypeResolver;
    }

    /**
     * Instantiate a Builder to be able to create a .
     * <p>
     * The {@link TransactionManager} is defaulted to a {@link NoTransactionManager}. The {@code useBinaryPojo} is
     * defaulted to {@code true}.
     * <p>
     * The {@link Scheduler}, {@link Serializer} and {@link EventBus} are <b>hard requirements</b> and as such should be
     * provided.
     *
     * @return a Builder to be able to create a
     */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    public ScheduleToken schedule(Instant triggerDateTime, Object event) {
        String identifier = IdentifierFactory.getInstance().generateIdentifier();
        DbSchedulerScheduleToken taskInstanceId = new DbSchedulerScheduleToken(identifier);
        try {
            TaskInstance<?> taskInstance;
            if (useBinaryPojo) {
                taskInstance = getBinaryTask(taskInstanceId, event);
            } else {
                taskInstance = getHumanReadableTask(taskInstanceId, event);
            }
            scheduler.schedule(taskInstance, triggerDateTime);
        } catch (Exception e) {
            throw new SchedulingException("An error occurred while scheduling an event.", e);
        }
        return taskInstanceId;
    }

    /**
     * Gives the {@link Task} using {@link DbSchedulerBinaryEventData} to publish an event via a {@link Scheduler}. To
     * be able to execute the task, this should be added to the task list, used to create the scheduler.
     *
     * @param eventSchedulerSupplier a {@link Supplier} of a . Preferably a method
     *                               involving dependency injection is used. When those are not available the
     *                               {@link DbSchedulerEventSchedulerSupplier} can be used instead.
     * @return a {@link Task} to publish an event
     */
    public static Task<DbSchedulerHumanReadableEventData> humanReadableTask(
            Supplier<DbSchedulerEventScheduler> eventSchedulerSupplier
    ) {
        return new Tasks.OneTimeTaskBuilder<>(TASK_NAME, DbSchedulerHumanReadableEventData.class)
                .execute((ti, context) -> {
                    DbSchedulerEventScheduler eventScheduler = eventSchedulerSupplier.get();
                    if (isNull(eventScheduler)) {
                        throw new EventSchedulerNotSuppliedException();
                    }
                    EventMessage eventMessage = eventScheduler.fromDbSchedulerEventData(ti.getData());
                    eventScheduler.publishEventMessage(eventMessage);
                });
    }

    /**
     * Gives the {@link Task} using {@link DbSchedulerHumanReadableEventData} to publish an event via a
     * {@link Scheduler}. To be able to execute the task, this should be added to the task list, used to create the
     * scheduler.
     *
     * @param eventSchedulerSupplier a {@link Supplier} of a . Preferably a method
     *                               involving dependency injection is used. When those are not available the
     *                               {@link DbSchedulerEventSchedulerSupplier} can be used instead.
     * @return a {@link Task} to publish an event
     */
    public static Task<DbSchedulerBinaryEventData> binaryTask(
            Supplier<DbSchedulerEventScheduler> eventSchedulerSupplier) {
        return new Tasks.OneTimeTaskBuilder<>(TASK_NAME, DbSchedulerBinaryEventData.class)
                .execute((ti, context) -> {
                    DbSchedulerEventScheduler eventScheduler = eventSchedulerSupplier.get();
                    if (isNull(eventScheduler)) {
                        throw new EventSchedulerNotSuppliedException();
                    }
                    EventMessage eventMessage = eventScheduler.fromDbSchedulerEventData(ti.getData());
                    eventScheduler.publishEventMessage(eventMessage);
                });
    }

    private TaskInstance<?> getBinaryTask(DbSchedulerScheduleToken taskInstanceId, Object event) {
        DbSchedulerBinaryEventData data;
        if (event instanceof EventMessage) {
            data = binaryDataFromEvent((EventMessage) event);
        } else {
            data = binaryDataFromObject(event);
        }
        return binaryTaskDescriptor.instance(taskInstanceId.getId()).data(data).build();
    }

    private DbSchedulerBinaryEventData binaryDataFromObject(Object event) {
        SerializedObject<byte[]> serialized = serializer.serialize(event, byte[].class);
        byte[] serializedPayload = serialized.getData();
        String payloadClass = serialized.getType().getName();
        String revision = serialized.getType().getRevision();
        return new DbSchedulerBinaryEventData(serializedPayload, payloadClass, revision, null);
    }

    private DbSchedulerBinaryEventData binaryDataFromEvent(EventMessage eventMessage) {
        SerializedObject<byte[]> serialized = serializer.serialize(eventMessage.payload(), byte[].class);
        byte[] serializedPayload = serialized.getData();
        String payloadClass = serialized.getType().getName();
        String revision = serialized.getType().getRevision();
        byte[] serializedMetadata = serializer.serialize(eventMessage.metadata(), byte[].class).getData();
        return new DbSchedulerBinaryEventData(serializedPayload, payloadClass, revision, serializedMetadata);
    }

    private TaskInstance<?> getHumanReadableTask(DbSchedulerScheduleToken taskInstanceId, Object event) {
        DbSchedulerHumanReadableEventData data;
        if (event instanceof EventMessage) {
            data = humanReadableDataFromEvent((EventMessage) event);
        } else {
            data = humanReadableDataFromObject(event);
        }
        return humanReadableTaskDescriptor.instance(taskInstanceId.getId()).data(data).build();
    }

    private DbSchedulerHumanReadableEventData humanReadableDataFromObject(Object event) {
        SerializedObject<String> serialized = serializer.serialize(event, String.class);
        String serializedPayload = serialized.getData();
        String payloadClass = serialized.getType().getName();
        String revision = serialized.getType().getRevision();
        return new DbSchedulerHumanReadableEventData(serializedPayload, payloadClass, revision, null);
    }

    private DbSchedulerHumanReadableEventData humanReadableDataFromEvent(EventMessage eventMessage) {
        SerializedObject<String> serialized = serializer.serialize(eventMessage.payload(), String.class);
        String serializedPayload = serialized.getData();
        String payloadClass = serialized.getType().getName();
        String revision = serialized.getType().getRevision();
        String serializedMetadata = serializer.serialize(eventMessage.metadata(), String.class).getData();
        return new DbSchedulerHumanReadableEventData(serializedPayload, payloadClass, revision, serializedMetadata);
    }

    private EventMessage fromDbSchedulerEventData(DbSchedulerBinaryEventData data) {
        SimpleSerializedObject<byte[]> serializedObject = new SimpleSerializedObject<>(
                data.getP(), byte[].class, data.getC(), data.getR()
        );
        Object deserializedPayload = serializer.deserialize(serializedObject);
        EventMessage eventMessage = asEventMessage(deserializedPayload);
        if (!isNull(data.getM())) {
            SimpleSerializedObject<byte[]> serializedMetadata = new SimpleSerializedObject<>(
                    data.getM(), byte[].class, Metadata.class.getName(), null
            );
            eventMessage = eventMessage.andMetadata(serializer.deserialize(serializedMetadata));
        }
        return eventMessage;
    }

    private EventMessage asEventMessage(Object event) {
        if (event instanceof EventMessage e) {
            return e;
        }
        if (event instanceof Message message) {
            return new GenericEventMessage(message);
        }
        return new GenericEventMessage(
                messageTypeResolver.resolveOrThrow(event),
                event,
                Metadata.emptyInstance()
        );
    }

    private EventMessage fromDbSchedulerEventData(DbSchedulerHumanReadableEventData data) {
        SimpleSerializedObject<String> serializedObject = new SimpleSerializedObject<>(
                data.getSerializedPayload(), String.class, data.getPayloadClass(), data.getRevision()
        );
        Object deserializedPayload = serializer.deserialize(serializedObject);
        EventMessage eventMessage = asEventMessage(deserializedPayload);
        if (!isNull(data.getSerializedMetadata())) {
            SimpleSerializedObject<String> serializedMetadata = new SimpleSerializedObject<>(
                    data.getSerializedMetadata(), String.class, Metadata.class.getName(), null
            );
            eventMessage = eventMessage.andMetadata(serializer.deserialize(serializedMetadata));
        }
        return eventMessage;
    }

    @Override
    public ScheduleToken schedule(Duration triggerDuration, Object event) {
        return schedule(ClockUtils.instant().plus(triggerDuration), event);
    }

    @Override
    public void cancelSchedule(ScheduleToken scheduleToken) {
        if (!(scheduleToken instanceof DbSchedulerScheduleToken reference)) {
            throw new IllegalArgumentException("The given ScheduleToken was not provided by this scheduler.");
        }
        scheduler.cancel(reference);
    }

    /**
     * Will start the {@link Scheduler} depending on its current state and the value of {@code startScheduler},
     */
    public void start() {
        if (!startScheduler) {
            return;
        }
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
        if (isShutdown.compareAndSet(false, true) && stopScheduler) {
            scheduler.stop();
        }
    }

    private void publishEventMessage(EventMessage eventMessage) {
        LegacyUnitOfWork<EventMessage> unitOfWork = LegacyDefaultUnitOfWork.startAndGet(null);
        unitOfWork.attachTransaction(transactionManager);
        unitOfWork.execute((ctx) -> eventBus.publish(null, eventMessage));
    }

    /**
     * Builder class to instantiate a {@link DbSchedulerEventScheduler}.
     * <p>
     * The {@link TransactionManager} is defaulted to a {@link NoTransactionManager}. The {@code useBinaryPojo} and
     * {@code startScheduler} are defaulted to {@code true}.
     * <p>
     * The {@link Scheduler}, {@link Serializer} and {@link EventBus} are <b>hard requirements</b> and as such should be
     * provided.
     */
    public static class Builder {

        private Scheduler scheduler;
        private Serializer serializer;
        private TransactionManager transactionManager = NoTransactionManager.INSTANCE;
        private EventBus eventBus;
        private boolean useBinaryPojo = true;
        private boolean startScheduler = true;
        private boolean stopScheduler = true;
        private MessageTypeResolver messageTypeResolver = new ClassBasedMessageTypeResolver();

        /**
         * Sets the {@link Scheduler} used for scheduling and triggering purposes of the events. It should have either
         * the {@link #binaryTask(Supplier)} or the {@link #humanReadableTask(Supplier)} from this class as one of its
         * tasks to work. Which one depends on the setting of {@code useBinaryPojo}. When {@code true}, use
         * {@link #binaryTask(Supplier)} else {@link #humanReadableTask(Supplier)}. Depending on you application, you
         * can manage when to start the scheduler, or leave {@code startScheduler} to true, to start it via the
         * {@link Lifecycle}.
         *
         * @param scheduler a {@link Scheduler} used for scheduling and triggering purposes of the events
         * @return the current Builder instance, for fluent interfacing
         */
        public Builder scheduler(Scheduler scheduler) {
            assertNonNull(scheduler, "Scheduler may not be null");
            this.scheduler = scheduler;
            return this;
        }

        /**
         * Sets the {@link Serializer} used to de-/serialize the {@code payload} and the {@link Metadata}.
         *
         * @param serializer a {@link Serializer} used to de-/serialize the {@code payload}, and {@link Metadata}.
         * @return the current Builder instance, for fluent interfacing
         */
        public Builder serializer(Serializer serializer) {
            assertNonNull(serializer, "Serializer may not be null");
            this.serializer = serializer;
            return this;
        }

        /**
         * Sets the {@link TransactionManager} used to build transactions and ties them on event publication. Defaults
         * to a {@link NoTransactionManager}.
         *
         * @param transactionManager a {@link TransactionManager} used to build transactions and ties them on event
         *                           publication
         * @return the current Builder instance, for fluent interfacing
         */
        public Builder transactionManager(TransactionManager transactionManager) {
            assertNonNull(transactionManager, "TransactionManager may not be null");
            this.transactionManager = transactionManager;
            return this;
        }

        /**
         * Sets the {@link EventBus} used to publish events on once the schedule has been met.
         *
         * @param eventBus a {@link EventBus} used to publish events on once the schedule has been met
         * @return the current Builder instance, for fluent interfacing
         */
        public Builder eventBus(EventBus eventBus) {
            assertNonNull(eventBus, "EventBus may not be null");
            this.eventBus = eventBus;
            return this;
        }

        /**
         * Sets whether to use a pojo optimized for size, {@link DbSchedulerBinaryEventData}, compared to a pojo
         * optimized for readability, {@link DbSchedulerEventScheduler}.
         *
         * @param useBinaryPojo a {@code boolean} to determine whether to use a binary format.
         * @return the current Builder instance, for fluent interfacing
         */
        public Builder useBinaryPojo(boolean useBinaryPojo) {
            this.useBinaryPojo = useBinaryPojo;
            return this;
        }

        /**
         * Sets whether to start the {@link Scheduler} using the {@link Lifecycle}, or to never start the scheduler from
         * this component instead. Defaults to {@code true}.
         *
         * @param startScheduler a {@code boolean} to determine whether to start the scheduler.
         * @return the current Builder instance, for fluent interfacing
         */
        public Builder startScheduler(boolean startScheduler) {
            this.startScheduler = startScheduler;
            return this;
        }

        /**
         * Sets whether to stop the {@link Scheduler} using the {@link Lifecycle}, or to never stop the scheduler from
         * this component instead. Defaults to {@code true}.
         *
         * @param stopScheduler a {@code boolean} to determine whether to stop the scheduler.
         * @return the current Builder instance, for fluent interfacing
         */
        public Builder stopScheduler(boolean stopScheduler) {
            this.stopScheduler = stopScheduler;
            return this;
        }

        /**
         * Sets the {@link MessageTypeResolver} used to resolve the {@link QualifiedName} when publishing {@link EventMessage EventMessages}.
         * If not set, a {@link ClassBasedMessageTypeResolver} is used by default.
         *
         * @param messageTypeResolver The {@link MessageTypeResolver} used to provide the {@link QualifiedName} for {@link EventMessage EventMessages}.
         * @return The current Builder instance, for fluent interfacing.
         */
        public Builder messageNameResolver(MessageTypeResolver messageTypeResolver) {
            assertNonNull(messageTypeResolver, "MessageNameResolver may not be null");
            this.messageTypeResolver = messageTypeResolver;
            return this;
        }

        /**
         * Initializes a {@link DbSchedulerEventScheduler} as specified through this Builder.
         *
         * @return a {@link DbSchedulerEventScheduler} as specified through this Builder
         */
        public DbSchedulerEventScheduler build() {
            return new DbSchedulerEventScheduler(this);
        }

        /**
         * Validates whether the fields contained in this Builder are set accordingly.
         *
         * @throws AxonConfigurationException if one field is asserted to be incorrect according to the Builder's
         *                                    specifications
         */
        protected void validate() throws AxonConfigurationException {
            assertNonNull(scheduler, "The Scheduler is a hard requirement and should be provided.");
            assertNonNull(serializer, "The Serializer is a hard requirement and should be provided.");
            assertNonNull(eventBus, "The EventBus is a hard requirement and should be provided.");
        }
    }
}