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

package org.axonframework.messaging.eventhandling.scheduling.jobrunr;

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
import org.axonframework.messaging.core.MessageTypeResolver;
import org.axonframework.messaging.core.Metadata;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.unitofwork.LegacyDefaultUnitOfWork;
import org.axonframework.messaging.unitofwork.LegacyUnitOfWork;
import org.axonframework.conversion.SerializedObject;
import org.axonframework.conversion.Serializer;
import org.axonframework.conversion.SimpleSerializedObject;
import org.jobrunr.jobs.JobId;
import org.jobrunr.scheduling.JobBuilder;
import org.jobrunr.scheduling.JobScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static java.util.Objects.isNull;
import static org.axonframework.common.BuilderUtils.assertNonNull;
import static org.axonframework.deadline.jobrunr.LabelUtils.getLabel;

/**
 * EventScheduler implementation that delegates scheduling and triggering to a JobRunr JobScheduler.
 *
 * @author Gerard Klijs
 * @since 4.7.0
 */
public class JobRunrEventScheduler implements EventScheduler {

    private static final Logger logger = LoggerFactory.getLogger(JobRunrEventScheduler.class);
    private final JobScheduler jobScheduler;
    private final String jobName;
    private final Serializer serializer;
    private final TransactionManager transactionManager;
    private final EventBus eventBus;
    private final MessageTypeResolver messageTypeResolver;

    /**
     * Instantiate a {@code JobRunrEventScheduler} based on the fields contained in the
     * {@link JobRunrEventScheduler.Builder}.
     * <p>
     * Will assert that the {@link JobScheduler}, {@link Serializer} and {@link EventBus} are not {@code null}, and will
     * throw an {@link AxonConfigurationException} if any of them is {@code null}.
     *
     * @param builder the {@link Builder} used to instantiate a {@code JobRunrEventScheduler} instance
     */
    protected JobRunrEventScheduler(Builder builder) {
        builder.validate();
        jobScheduler = builder.jobScheduler;
        jobName = builder.jobName;
        serializer = builder.serializer;
        transactionManager = builder.transactionManager;
        eventBus = builder.eventBus;
        messageTypeResolver = builder.messageTypeResolver;
    }

    /**
     * Instantiate a Builder to be able to create a {@code JobRunrEventScheduler}.
     * <p>
     * The {@link TransactionManager} is defaulted to a {@link NoTransactionManager}.
     * <p>
     * The {@link JobScheduler}, {@link Serializer} and {@link EventBus} are <b>hard requirements</b> and as such should
     * be provided.
     *
     * @return a Builder to be able to create a {@code JobRunrEventScheduler}
     */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    public ScheduleToken schedule(Instant triggerDateTime, Object event) {
        UUID jobIdentifier = UUID.randomUUID();
        try {
            JobBuilder job = JobBuilder.aJob()
                                       .withId(jobIdentifier)
                                       .withName(jobName)
                                       .withLabels(getLabel(jobName))
                                       .scheduleAt(triggerDateTime);
            if (event instanceof EventMessage) {
                addDetailsFromEvent(job, (EventMessage) event);
            } else {
                addDetailsFromObject(job, event);
            }
            JobId id = jobScheduler.create(job);
            logger.debug("Job with id: [{}] was successfully created.", id);
        } catch (Exception e) {
            throw new SchedulingException("An error occurred while scheduling an event.", e);
        }
        return new JobRunrScheduleToken(jobIdentifier);
    }

    private void addDetailsFromObject(JobBuilder job, Object event) {
        SerializedObject<String> serialized = serializer.serialize(event, String.class);
        String serializedPayload = serialized.getData();
        String payloadClass = serialized.getType().getName();
        String revision = serialized.getType().getRevision();
        if (isNull(revision)) {
            job.withDetails(() -> publish(serializedPayload, payloadClass));
        } else {
            job.withDetails(() -> publishWithRevision(serializedPayload, payloadClass, revision));
        }
    }

    private void addDetailsFromEvent(JobBuilder job, EventMessage eventMessage) {
        SerializedObject<String> serialized = serializer.serialize(eventMessage.payload(), String.class);
        String serializedPayload = serialized.getData();
        String payloadClass = serialized.getType().getName();
        String revision = serialized.getType().getRevision();
        String serializedMetadata = serializer.serialize(eventMessage.metadata(), String.class).getData();
        if (isNull(revision)) {
            job.withDetails(() -> publish(serializedPayload, payloadClass, serializedMetadata));
        } else {
            job.withDetails(() -> publishWithRevision(serializedPayload,
                                                      payloadClass,
                                                      revision,
                                                      serializedMetadata));
        }
    }

    @Override
    public ScheduleToken schedule(Duration triggerDuration, Object event) {
        return schedule(ClockUtils.instant().plus(triggerDuration), event);
    }

    @Override
    public void cancelSchedule(ScheduleToken scheduleToken) {
        if (!(scheduleToken instanceof JobRunrScheduleToken reference)) {
            throw new IllegalArgumentException("The given ScheduleToken was not provided by this scheduler.");
        }
        jobScheduler.delete(reference.getJobIdentifier(), "Deleted via Axon EventScheduler API");
    }

    @Override
    public void shutdown() {
        jobScheduler.shutdown();
    }

    /**
     * This function should only be called via Jobrunr when a scheduled event was triggered. It will create an
     * {@link EventMessage} and publish it using the {@link EventBus}.
     *
     * @param serializedPayload The {@link String} with the serialized payload.
     * @param payloadClass      The {@link String} with the name of the class ot the payload.
     */
    public void publish(String serializedPayload, String payloadClass) {
        logger.debug("Invoked by JobRunr to publish a scheduled event without metadata.");
        EventMessage eventMessage = createMessage(serializedPayload, payloadClass, null);
        publishEventMessage(eventMessage);
    }

    /**
     * This function should only be called via Jobrunr when a scheduled event was triggered. It will create an
     * {@link EventMessage} and publish it using the {@link EventBus}.
     *
     * @param serializedPayload  The {@link String} with the serialized payload.
     * @param payloadClass       The {@link String} with the name of the class of the payload.
     * @param serializedMetadata The {@link String} with the serialized metadata.
     */
    public void publish(String serializedPayload, String payloadClass, String serializedMetadata) {
        logger.debug("Invoked by JobRunr to publish a scheduled event with metadata");
        EventMessage eventMessage = createMessage(serializedPayload, payloadClass, null, serializedMetadata);
        publishEventMessage(eventMessage);
    }

    /**
     * This function should only be called via Jobrunr when a scheduled event was triggered. It will create an
     * {@link EventMessage} and publish it using the {@link EventBus}.
     *
     * @param serializedPayload The {@link String} with the serialized payload.
     * @param payloadClass      The {@link String} with the name of the class ot the payload.
     * @param revision          The {@link String} with the revision of the class of the payload. Might be null.
     */
    public void publishWithRevision(String serializedPayload, String payloadClass, String revision) {
        logger.debug("Invoked by JobRunr to publish a scheduled event without metadata.");
        EventMessage eventMessage = createMessage(serializedPayload, payloadClass, revision);
        publishEventMessage(eventMessage);
    }

    /**
     * This function should only be called via Jobrunr when a scheduled event was triggered. It will create an
     * {@link EventMessage} and publish it using the {@link EventBus}.
     *
     * @param serializedPayload  The {@link String} with the serialized payload.
     * @param payloadClass       The {@link String} with the name of the class of the payload.
     * @param revision           The {@link String} with the revision of the class of the payload. Might be null.
     * @param serializedMetadata The {@link String} with the serialized metadata.
     */
    public void publishWithRevision(String serializedPayload, String payloadClass, String revision,
                                    String serializedMetadata) {
        logger.debug("Invoked by JobRunr to publish a scheduled event with metadata");
        EventMessage eventMessage = createMessage(serializedPayload, payloadClass, revision, serializedMetadata);
        publishEventMessage(eventMessage);
    }

    private EventMessage createMessage(
            String serializedPayload,
            String payloadClass,
            String revision) {
        SimpleSerializedObject<String> serializedObject = new SimpleSerializedObject<>(
                serializedPayload, String.class, payloadClass, revision
        );
        Object deserializedPayload = serializer.deserialize(serializedObject);
        return asEventMessage(deserializedPayload);
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

    private EventMessage createMessage(
            String serializedPayload,
            String payloadClass,
            String revision,
            String serializedMetadata) {
        EventMessage eventMessage = createMessage(serializedPayload, payloadClass, revision);
        SimpleSerializedObject<String> serialized = new SimpleSerializedObject<>(
                serializedMetadata, String.class, Metadata.class.getName(), null
        );
        return eventMessage.andMetadata(serializer.deserialize(serialized));
    }

    private void publishEventMessage(EventMessage eventMessage) {
        LegacyUnitOfWork<EventMessage> unitOfWork = LegacyDefaultUnitOfWork.startAndGet(null);
        unitOfWork.attachTransaction(transactionManager);
        unitOfWork.execute((ctx) -> eventBus.publish(null, eventMessage));
    }

    /**
     * Builder class to instantiate a {@link JobRunrEventScheduler}.
     * <p>
     * The {@link TransactionManager} is defaulted to a {@link NoTransactionManager}.
     * <p>
     * The {@link JobScheduler}, {@link Serializer} and {@link EventBus} are <b>hard requirements</b> and as such should
     * be provided.
     */
    public static class Builder {

        private JobScheduler jobScheduler;

        private String jobName = "AxonScheduledEvent";
        private Serializer serializer;
        private TransactionManager transactionManager = NoTransactionManager.INSTANCE;
        private EventBus eventBus;
        private MessageTypeResolver messageTypeResolver = new ClassBasedMessageTypeResolver();

        /**
         * Sets the {@link JobScheduler} used for scheduling and triggering purposes of the events.
         *
         * @param jobScheduler a {@link JobScheduler} used for scheduling and triggering purposes of the events
         * @return the current Builder instance, for fluent interfacing
         */
        public Builder jobScheduler(JobScheduler jobScheduler) {
            assertNonNull(jobScheduler, "JobScheduler may not be null");
            this.jobScheduler = jobScheduler;
            return this;
        }

        /**
         * Sets the {@link String} used for creating the scheduled jobs in JobRunr. Defaults to
         * {@code AxonScheduledEvent}.
         *
         * @param jobName a {@link JobScheduler} used for naming the job
         * @return the current Builder instance, for fluent interfacing
         */
        public Builder jobName(String jobName) {
            assertNonNull(jobName, "JobName may not be null");
            this.jobName = jobName;
            return this;
        }

        /**
         * Sets the {@link Serializer} used to de-/serialize the {@code payload} and the
         * {@link Metadata}.
         *
         * @param serializer a {@link Serializer} used to de-/serialize the {@code payload}, and
         *                   {@link Metadata}.
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
         * Sets the {@link MessageTypeResolver} used to resolve the {@link QualifiedName} when publishing
         * {@link EventMessage EventMessages}. If not set, a {@link ClassBasedMessageTypeResolver} is used by default.
         *
         * @param messageTypeResolver The {@link MessageTypeResolver} used to provide the {@link QualifiedName} for
         *                            {@link EventMessage EventMessages}.
         * @return The current Builder instance, for fluent interfacing.
         */
        public Builder messageNameResolver(MessageTypeResolver messageTypeResolver) {
            assertNonNull(messageTypeResolver, "MessageNameResolver may not be null");
            this.messageTypeResolver = messageTypeResolver;
            return this;
        }

        /**
         * Initializes a {@link JobRunrEventScheduler} as specified through this Builder.
         *
         * @return a {@link JobRunrEventScheduler} as specified through this Builder
         */
        public JobRunrEventScheduler build() {
            return new JobRunrEventScheduler(this);
        }

        /**
         * Validates whether the fields contained in this Builder are set accordingly.
         *
         * @throws AxonConfigurationException if one field is asserted to be incorrect according to the Builder's
         *                                    specifications
         */
        protected void validate() throws AxonConfigurationException {
            assertNonNull(jobScheduler, "The JobScheduler is a hard requirement and should be provided.");
            assertNonNull(serializer, "The Serializer is a hard requirement and should be provided.");
            assertNonNull(eventBus, "The EventBus is a hard requirement and should be provided.");
        }
    }
}
