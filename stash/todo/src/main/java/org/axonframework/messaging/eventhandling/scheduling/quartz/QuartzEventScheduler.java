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

package org.axonframework.messaging.eventhandling.scheduling.quartz;

import org.axonframework.common.Assert;
import org.axonframework.common.AxonConfigurationException;
import org.axonframework.common.ClockUtils;
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
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.MessageTypeResolver;
import org.axonframework.messaging.core.Metadata;
import org.axonframework.conversion.SerializedObject;
import org.axonframework.conversion.Serializer;
import org.axonframework.conversion.SimpleSerializedObject;
import org.quartz.JobBuilder;
import org.quartz.JobDataMap;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.function.Supplier;

import static org.axonframework.common.BuilderUtils.assertNonNull;
import static org.axonframework.messaging.eventhandling.scheduling.quartz.FireEventJob.*;
import static org.quartz.JobKey.jobKey;

/**
 * EventScheduler implementation that delegates scheduling and triggering to a Quartz Scheduler.
 *
 * @author Allard Buijze
 * @see EventJobDataBinder
 * @see FireEventJob
 * @since 0.7
 */
public class QuartzEventScheduler implements EventScheduler {

    private static final Logger logger = LoggerFactory.getLogger(QuartzEventScheduler.class);

    private static final String JOB_NAME_PREFIX = "event-";
    private static final String DEFAULT_GROUP_NAME = "AxonFramework-Events";

    private final Scheduler scheduler;
    private final EventBus eventBus;
    private final EventJobDataBinder jobDataBinder;
    private final TransactionManager transactionManager;
    private final MessageTypeResolver messageTypeResolver;

    private String groupIdentifier = DEFAULT_GROUP_NAME;
    private volatile boolean initialized;

    /**
     * Instantiate a  based on the fields contained in the {@link Builder}.
     * <p>
     * Will assert that the {@link Scheduler} and {@link EventBus} are not {@code null}, and will throw an
     * {@link AxonConfigurationException} if any of them is {@code null}. The EventBus, TransactionManager and
     * EventJobDataBinder will be tied to the Scheduler's context. If this initialization step fails, this will too
     * result in an AxonConfigurationException.
     *
     * @param builder the {@link Builder} used to instantiate a  instance
     */
    protected QuartzEventScheduler(Builder builder) {
        builder.validate();
        scheduler = builder.scheduler;
        eventBus = builder.eventBus;
        jobDataBinder = builder.jobDataBinderSupplier.get();
        transactionManager = builder.transactionManager;
        messageTypeResolver = builder.messageTypeResolver;

        try {
            initialize();
        } catch (SchedulerException e) {
            throw new AxonConfigurationException("Unable to initialize QuartzEventScheduler", e);
        }
    }

    private void initialize() throws SchedulerException {
        scheduler.getContext().put(EVENT_BUS_KEY, eventBus);
        scheduler.getContext().put(TRANSACTION_MANAGER_KEY, transactionManager);
        scheduler.getContext().put(EVENT_JOB_DATA_BINDER_KEY, jobDataBinder);
        scheduler.getContext().put(MESSAGE_TYPE_RESOLVER_KEY, messageTypeResolver);
        initialized = true;
    }

    /**
     * Instantiate a Builder to be able to create a .
     * <p>
     * The {@link EventJobDataBinder} is defaulted to a {@link DirectEventJobDataBinder} using the configured
     * {@link Serializer}, and the {@link TransactionManager} defaults to a {@link NoTransactionManager}. Note that if
     * the {@code Serializer} is not set, the configuration expects the {@code EventJobDataBinder} to be set.
     * <p>
     * The {@link Scheduler} and {@link EventBus} are <b>hard requirements</b> and as such should be provided.
     *
     * @return a Builder to be able to create a
     */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    public ScheduleToken schedule(Instant triggerDateTime, Object event) {
        Assert.state(initialized, () -> "Scheduler is not yet initialized");
        EventMessage eventMessage = asEventMessage(event);
        String jobIdentifier = JOB_NAME_PREFIX + eventMessage.identifier();
        QuartzScheduleToken tr = new QuartzScheduleToken(jobIdentifier, groupIdentifier);
        try {
            JobDetail jobDetail = buildJobDetail(eventMessage, new JobKey(jobIdentifier, groupIdentifier));
            scheduler.scheduleJob(jobDetail, buildTrigger(triggerDateTime, jobDetail.getKey()));
        } catch (SchedulerException e) {
            throw new SchedulingException("An error occurred while scheduling an event.", e);
        }
        return tr;
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


    /**
     * Builds the JobDetail instance for Quartz, which defines the Job that needs to be executed when the trigger
     * fires.
     * <p/>
     * The resulting JobDetail must be identified by the given {@code jobKey} and represent a Job that dispatches the
     * given {@code event}.
     * <p/>
     * This method may be safely overridden to change behavior. Defaults to a JobDetail to fire a {@link FireEventJob}.
     *
     * @param event  The event to be scheduled for dispatch
     * @param jobKey The key of the Job to schedule
     * @return a JobDetail describing the Job to be executed
     */
    protected JobDetail buildJobDetail(EventMessage event, JobKey jobKey) {
        JobDataMap jobData = jobDataBinder.toJobData(event);
        return JobBuilder.newJob(FireEventJob.class)
                         .withDescription(event.type().name())
                         .withIdentity(jobKey)
                         .usingJobData(jobData)
                         .build();
    }

    /**
     * Builds a Trigger which fires the Job identified by {@code jobKey} at (or around) the given
     * {@code triggerDateTime}.
     *
     * @param triggerDateTime The time at which a trigger was requested
     * @param jobKey          The key of the job to be triggered
     * @return a configured Trigger for the Job with key {@code jobKey}
     */
    protected Trigger buildTrigger(Instant triggerDateTime, JobKey jobKey) {
        return TriggerBuilder.newTrigger()
                             .forJob(jobKey)
                             .startAt(Date.from(triggerDateTime))
                             .build();
    }

    @Override
    public ScheduleToken schedule(Duration triggerDuration, Object event) {
        return schedule(ClockUtils.instant().plus(triggerDuration), event);
    }

    @Override
    public void cancelSchedule(ScheduleToken scheduleToken) {
        if (!(scheduleToken instanceof QuartzScheduleToken reference)) {
            throw new IllegalArgumentException("The given ScheduleToken was not provided by this scheduler.");
        }
        Assert.state(initialized, () -> "Scheduler is not yet initialized");

        try {
            if (!scheduler.deleteJob(jobKey(reference.getJobIdentifier(), reference.getGroupIdentifier()))) {
                logger.warn("The job belonging to this token could not be deleted.");
            }
        } catch (SchedulerException e) {
            throw new SchedulingException("An error occurred while cancelling a timer for a saga", e);
        }
    }

    /**
     * Sets the group identifier to use when scheduling jobs with Quartz. Defaults to "AxonFramework-Events".
     *
     * @param groupIdentifier the group identifier to use when scheduling jobs with Quartz
     */
    public void setGroupIdentifier(String groupIdentifier) {
        this.groupIdentifier = groupIdentifier;
    }

    @Override
    public void shutdown() {
        try {
            scheduler.shutdown(true);
        } catch (SchedulerException e) {
            throw new SchedulingException("An error occurred while trying to shutdown the event scheduler", e);
        }
    }

    /**
     * Binds an {@link EventMessage} to the {@link JobDataMap} by serializing the payload and metadata with a
     * {@link Serializer}. All the important EventMessage fields, thus the message identifier, timestamp, and the
     * serialized payload and metadata, are stored as separate values in the JobDataMap.
     * <p>
     * The old approach, which let Quartz do the conversion of the entire EventMessage at once, is maintained for
     * backwards compatibility only.
     */
    public static class DirectEventJobDataBinder implements EventJobDataBinder {

        /**
         * Key pointing to a message identifier.
         */
        public static final String MESSAGE_ID = "axon-message-id";
        /**
         * Key pointing to the serialized payload of a message.
         */
        public static final String SERIALIZED_MESSAGE_PAYLOAD = "axon-serialized-message-payload";
        /**
         * Key pointing to the payload type of a message.
         */
        public static final String MESSAGE_TYPE = "axon-message-type";
        /**
         * Key pointing to the revision of a message.
         */
        public static final String MESSAGE_REVISION = "axon-message-revision";
        /**
         * Key pointing to the timestamp of a message.
         */
        public static final String MESSAGE_TIMESTAMP = "axon-message-timestamp";
        /**
         * Key pointing to the {@link Metadata} of a message.
         */
        public static final String MESSAGE_METADATA = "axon-metadata";
        /**
         * Key pointing to the {@link Message#type()} as a {@code String} of the deadline in the {@link JobDataMap}.
         */
        public static final String TYPE = "type";

        private final Serializer serializer;

        /**
         * Instantiate a {@code DirectEventJobDataBinder} with the provided {@link Serializer} for de-/serializing event
         * messages.
         *
         * @param serializer the {@link Serializer} used for de-/serializing event messages
         */
        public DirectEventJobDataBinder(Serializer serializer) {
            this.serializer = serializer;
        }

        @Override
        public JobDataMap toJobData(Object event) {
            JobDataMap jobData = new JobDataMap();

            EventMessage eventMessage = (EventMessage) event;

            jobData.put(MESSAGE_ID, eventMessage.identifier());
            jobData.put(TYPE, eventMessage.type().toString());
            jobData.put(MESSAGE_TIMESTAMP, eventMessage.timestamp().toString());

            SerializedObject<byte[]> serializedPayload =
                    serializer.serialize(eventMessage.payload(), byte[].class);
            jobData.put(SERIALIZED_MESSAGE_PAYLOAD, serializedPayload.getData());
            jobData.put(MESSAGE_TYPE, serializedPayload.getType().getName());
            jobData.put(MESSAGE_REVISION, serializedPayload.getType().getRevision());

            SerializedObject<byte[]> serializedMetadata =
                    serializer.serialize(eventMessage.metadata(), byte[].class);
            jobData.put(MESSAGE_METADATA, serializedMetadata.getData());

            return jobData;
        }

        @Override
        public Object fromJobData(JobDataMap jobDataMap) {
            return new GenericEventMessage((String) jobDataMap.get(MESSAGE_ID),
                                             MessageType.fromString((String) jobDataMap.get(TYPE)),
                                             deserializePayload(jobDataMap),
                                             deserializeMetadata(jobDataMap),
                                             retrieveDeadlineTimestamp(jobDataMap));
        }

        private Object deserializePayload(JobDataMap jobDataMap) {
            SimpleSerializedObject<byte[]> serializedPayload = new SimpleSerializedObject<>(
                    (byte[]) jobDataMap.get(SERIALIZED_MESSAGE_PAYLOAD),
                    byte[].class,
                    (String) jobDataMap.get(MESSAGE_TYPE),
                    (String) jobDataMap.get(MESSAGE_REVISION)
            );
            return serializer.deserialize(serializedPayload);
        }

        private Map<String, String> deserializeMetadata(JobDataMap jobDataMap) {
            SimpleSerializedObject<byte[]> serializedDeadlineMetadata = new SimpleSerializedObject<>(
                    (byte[]) jobDataMap.get(MESSAGE_METADATA), byte[].class, Metadata.class.getName(), null
            );
            return serializer.deserialize(serializedDeadlineMetadata);
        }

        private Instant retrieveDeadlineTimestamp(JobDataMap jobDataMap) {
            Object timestamp = jobDataMap.get(MESSAGE_TIMESTAMP);
            if (timestamp instanceof String) {
                return Instant.parse(timestamp.toString());
            }
            return Instant.ofEpochMilli((long) timestamp);
        }
    }

    /**
     * Builder class to instantiate a {@link QuartzEventScheduler}.
     * <p>
     * The {@link EventJobDataBinder} is defaulted to a {@link DirectEventJobDataBinder} using the configured
     * {@link Serializer}, and the {@link TransactionManager} defaults to a {@link NoTransactionManager}. Note that if
     * the {@code Serializer} is not set, the configuration expects the {@code EventJobDataBinder} to be set.
     * <p>
     * The {@link Scheduler} and {@link EventBus} are <b>hard requirements</b> and as such should be provided.
     */
    public static class Builder {

        private Scheduler scheduler;
        private EventBus eventBus;
        private Supplier<EventJobDataBinder> jobDataBinderSupplier;
        private TransactionManager transactionManager = NoTransactionManager.INSTANCE;
        private Supplier<Serializer> serializer;
        private MessageTypeResolver messageTypeResolver = new ClassBasedMessageTypeResolver();

        /**
         * Sets the {@link Scheduler} used for scheduling and triggering purposes of the deadlines.
         *
         * @param scheduler a {@link Scheduler} used for scheduling and triggering purposes of the deadlines
         * @return the current Builder instance, for fluent interfacing
         */
        public Builder scheduler(Scheduler scheduler) {
            assertNonNull(scheduler, "Scheduler may not be null");
            this.scheduler = scheduler;
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
         * Sets the {@link EventJobDataBinder} instance which reads / writes the event message to publish to the
         * {@link JobDataMap}. Defaults to {@link DirectEventJobDataBinder}.
         *
         * @param jobDataBinder a {@link EventJobDataBinder} instance which reads / writes the event message to publish
         *                      to the {@link JobDataMap}
         * @return the current Builder instance, for fluent interfacing
         */
        public Builder jobDataBinder(EventJobDataBinder jobDataBinder) {
            assertNonNull(jobDataBinder, "EventJobDataBinder may not be null");
            this.jobDataBinderSupplier = () -> jobDataBinder;
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
         * Sets the {@link Serializer} used by the {@link EventJobDataBinder}. The {@code EventJobDataBinder} uses the
         * {@code Serializer} to de-/serialize the scheduled event.
         *
         * @param serializer a {@link Serializer} used by the {@link EventJobDataBinder} when serializing events
         * @return the current Builder instance, for fluent interfacing
         */
        public Builder serializer(Serializer serializer) {
            assertNonNull(serializer, "Serializer may not be null");
            this.serializer = () -> serializer;
            return this;
        }

        /**
         * Sets the {@link MessageTypeResolver} used to resolve the {@link MessageType} when publishing
         * {@link EventMessage EventMessages}. If not set, a {@link ClassBasedMessageTypeResolver} is used by default.
         *
         * @param messageTypeResolver The {@link MessageTypeResolver} used to provide the {@link MessageType} for
         *                            {@link EventMessage EventMessages}.
         * @return The current Builder instance, for fluent interfacing.
         */
        public Builder messageNameResolver(MessageTypeResolver messageTypeResolver) {
            assertNonNull(messageTypeResolver, "MessageNameResolver may not be null");
            this.messageTypeResolver = messageTypeResolver;
            return this;
        }

        /**
         * Initializes a {@link QuartzEventScheduler} as specified through this Builder.
         *
         * @return a {@link QuartzEventScheduler} as specified through this Builder
         */
        public QuartzEventScheduler build() {
            return new QuartzEventScheduler(this);
        }

        /**
         * Validates whether the fields contained in this Builder are set accordingly.
         *
         * @throws AxonConfigurationException if one field is asserted to be incorrect according to the Builder's
         *                                    specifications
         */
        protected void validate() throws AxonConfigurationException {
            assertNonNull(scheduler, "The Scheduler is a hard requirement and should be provided");
            assertNonNull(eventBus, "The EventBus is a hard requirement and should be provided");
            assertNonNull(serializer, "The Serializer is a hard requirement and should be provided");
            if (jobDataBinderSupplier == null) {
                jobDataBinderSupplier = () -> new DirectEventJobDataBinder(serializer.get());
            }
        }
    }
}
