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

package org.axonframework.deadline.quartz;

import org.axonframework.messaging.unitofwork.LegacyDefaultUnitOfWork;
import org.axonframework.messaging.core.ResultMessage;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.MessageHandlerInterceptorChain;
import org.axonframework.messaging.core.*;
import org.axonframework.messaging.core.unitofwork.transaction.TransactionManager;
import org.axonframework.deadline.DeadlineManagerSpanFactory;
import org.axonframework.deadline.DeadlineMessage;
import org.axonframework.deadline.GenericDeadlineMessage;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.conversion.SerializedObject;
import org.axonframework.conversion.Serializer;
import org.axonframework.conversion.SimpleSerializedObject;
import org.axonframework.messaging.tracing.Span;
import org.axonframework.messaging.tracing.SpanScope;
import org.quartz.Job;
import org.quartz.JobDataMap;
import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.SchedulerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Iterator;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static org.axonframework.deadline.quartz.DeadlineJob.DeadlineJobDataBinder.deadlineMessage;
import static org.axonframework.deadline.quartz.DeadlineJob.DeadlineJobDataBinder.deadlineScope;

/**
 * Quartz job which depicts handling of a scheduled deadline message. The {@link DeadlineMessage} and
 * {@link ScopeDescriptor} are retrieved from the {@link JobExecutionContext}. The {@link TransactionManager} and
 * {@link ScopeAware} components are fetched from {@link SchedulerContext}.
 *
 * @author Milan Savic
 * @author Steven van Beelen
 * @since 3.3
 */
public class DeadlineJob implements Job {

    private static final Logger logger = LoggerFactory.getLogger(DeadlineJob.class);

    /**
     * The key under which the {@link TransactionManager} is stored within the {@link SchedulerContext}.
     */
    public static final String TRANSACTION_MANAGER_KEY = TransactionManager.class.getName();

    /**
     * The key under which the {@link ScopeAwareProvider} is stored within the {@link SchedulerContext}.
     */
    public static final String SCOPE_AWARE_RESOLVER = ScopeAwareProvider.class.getName();

    /**
     * The key under which the {@link Serializer} is stored within the {@link SchedulerContext}.
     */
    public static final String JOB_DATA_SERIALIZER = Serializer.class.getName();

    /**
     * The key under which the {@link DeadlineManagerSpanFactory} is stored within the {@link SchedulerContext}.
     */
    public static final String SPAN_FACTORY = DeadlineManagerSpanFactory.class.getName();

    /**
     * The key under which the {@link MessageHandlerInterceptor}s are stored within the {@link SchedulerContext}.
     */
    public static final String HANDLER_INTERCEPTORS = MessageHandlerInterceptor.class.getName();

    /**
     * The key under which a {@link Predicate} is stored within the {@link SchedulerContext}. Used to decided whether a
     * job should be 'refired' immediately for a given {@link Throwable}.
     */
    public static final String REFIRE_IMMEDIATELY_POLICY = "refireImmediatelyPolicy";

    private static final Boolean REFIRE_IMMEDIATELY = true;

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        if (logger.isDebugEnabled()) {
            logger.debug("Starting a deadline job");
        }

        JobDetail jobDetail = context.getJobDetail();
        JobDataMap jobData = jobDetail.getJobDataMap();

        SchedulerContext schedulerContext;
        try {
            schedulerContext = context.getScheduler().getContext();
        } catch (Exception e) {
            logger.error("Exception occurred during processing a deadline job [{}]", jobDetail.getDescription(), e);
            throw new JobExecutionException(e);
        }

        Serializer serializer = (Serializer) schedulerContext.get(JOB_DATA_SERIALIZER);
        TransactionManager transactionManager = (TransactionManager) schedulerContext.get(TRANSACTION_MANAGER_KEY);
        ScopeAwareProvider scopeAwareComponents = (ScopeAwareProvider) schedulerContext.get(SCOPE_AWARE_RESOLVER);
        DeadlineManagerSpanFactory spanFactory = (DeadlineManagerSpanFactory) schedulerContext.get(SPAN_FACTORY);
        @SuppressWarnings("unchecked")
        List<MessageHandlerInterceptor<? super DeadlineMessage>> handlerInterceptors =
                (List<MessageHandlerInterceptor<? super DeadlineMessage>>)
                        schedulerContext.get(HANDLER_INTERCEPTORS);

        DeadlineMessage deadlineMessage = deadlineMessage(serializer, jobData);
        ScopeDescriptor deadlineScope = deadlineScope(serializer, jobData);

        Span span = spanFactory.createExecuteSpan(context.getTrigger().getJobKey().getGroup(),
                                                  context.getTrigger().getJobKey().getName(),
                                                  deadlineMessage);
        try (SpanScope unused = span.start()) {
            LegacyDefaultUnitOfWork<DeadlineMessage> unitOfWork =
                    LegacyDefaultUnitOfWork.startAndGet(deadlineMessage);
            unitOfWork.attachTransaction(transactionManager);

                // Interceptors are declared against a super type of DeadlineMessage, so each can handle the
            // deadline being triggered here; narrowing them lets the chain below be typed against it.
            @SuppressWarnings("unchecked")
            List<MessageHandlerInterceptor<DeadlineMessage>> narrowed =
                    (List<MessageHandlerInterceptor<DeadlineMessage>>) (List<?>) handlerInterceptors;
            Iterator<MessageHandlerInterceptor<DeadlineMessage>> interceptors = narrowed.iterator();
            MessageHandlerInterceptorChain<DeadlineMessage> chain =
                    new MessageHandlerInterceptorChain<>() {
                    @Override
                    public MessageStream<?> proceed(DeadlineMessage message, ProcessingContext context) {
                        try {
                            if (interceptors.hasNext()) {
                                return interceptors.next().interceptOnHandle(message, context, this);
                            }
                            executeScheduledDeadline(scopeAwareComponents, message, context, deadlineScope);
                            return MessageStream.empty();
                        } catch (Exception e) {
                            return MessageStream.failed(e);
                        }
                    }
                    };

            ResultMessage resultMessage = unitOfWork.executeWithResult(
                    processingContext -> chain.proceed(unitOfWork.getMessage(), processingContext)
            );
            if (resultMessage != null && resultMessage.payload() instanceof Throwable exceptionResult) {
                span.recordException(exceptionResult);

                @SuppressWarnings("unchecked")
                Predicate<Throwable> refirePolicy =
                        (Predicate<Throwable>) schedulerContext.get(REFIRE_IMMEDIATELY_POLICY);
                if (refirePolicy.test(exceptionResult)) {
                    logger.error("Exception occurred during processing a deadline job which will be retried [{}]",
                                 jobDetail.getDescription(), exceptionResult);
                    throw new JobExecutionException(exceptionResult, REFIRE_IMMEDIATELY);
                }
                logger.error("Exception occurred during processing a deadline job [{}]",
                             jobDetail.getDescription(), exceptionResult);
                throw new JobExecutionException(exceptionResult);
            } else if (logger.isInfoEnabled()) {
                logger.info("Job successfully executed. Deadline message [{}] processed.",
                            deadlineMessage.type().name());
            }
        }
    }

    private void executeScheduledDeadline(ScopeAwareProvider scopeAwareComponents,
                                          DeadlineMessage deadlineMessage,
                                          ProcessingContext context,
                                          ScopeDescriptor deadlineScope) {
        scopeAwareComponents.provideScopeAwareStream(deadlineScope)
                            .filter(scopeAwareComponent -> scopeAwareComponent.canResolve(deadlineScope))
                            .forEach(scopeAwareComponent -> {
                                try {
                                    scopeAwareComponent.send(deadlineMessage, context, deadlineScope);
                                } catch (Exception e) {
                                    String exceptionMessage = String.format(
                                            "Failed to send a DeadlineMessage for scope [%s]",
                                            deadlineScope.scopeDescription()
                                    );
                                    throw new ExecutionException(exceptionMessage, e);
                                }
                            });
    }

    /**
     * This binder is used to map deadline message and deadline scopes to the job data and vice versa.
     */
    public static class DeadlineJobDataBinder {

        /**
         * Key pointing to the serialized deadline {@link ScopeDescriptor} in the {@link JobDataMap}
         */
        public static final String SERIALIZED_DEADLINE_SCOPE = "serializedDeadlineScope";
        /**
         * Key pointing to the class name of the deadline {@link ScopeDescriptor} in the {@link JobDataMap}
         */
        public static final String SERIALIZED_DEADLINE_SCOPE_CLASS_NAME = "serializedDeadlineScopeClassName";
        /**
         * Key pointing to the {@link Message#type()} as a {@code String} of the deadline in the {@link JobDataMap}.
         */
        public static final String TYPE = "type";
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
         * Key pointing to the deadline name of a {@link org.axonframework.deadline.DeadlineMessage}.
         */
        public static final String DEADLINE_NAME = "axon-deadline-name";

        /**
         * Serializes the provided {@code deadlineMessage} and {@code deadlineScope} and puts them in a
         * {@link JobDataMap}.
         *
         * @param serializer      the {@link Serializer} used to serialize the given {@code deadlineMessage} and
         *                        {@code deadlineScope}
         * @param deadlineMessage the {@link DeadlineMessage} to be handled
         * @param deadlineScope   the {@link ScopeDescriptor} of the {@link Scope} the
         *                        {@code deadlineMessage} should go to.
         * @return a {@link JobDataMap} containing the {@code deadlineMessage} and {@code deadlineScope}
         */
        public static JobDataMap toJobData(Serializer serializer,
                                           DeadlineMessage deadlineMessage,
                                           ScopeDescriptor deadlineScope) {
            JobDataMap jobData = new JobDataMap();
            putDeadlineMessage(jobData, deadlineMessage, serializer);
            putDeadlineScope(jobData, deadlineScope, serializer);
            return jobData;
        }

        @SuppressWarnings("Duplicates")
        private static void putDeadlineMessage(JobDataMap jobData,
                                               DeadlineMessage deadlineMessage,
                                               Serializer serializer) {
            jobData.put(DEADLINE_NAME, deadlineMessage.getDeadlineName());
            jobData.put(MESSAGE_ID, deadlineMessage.identifier());
            jobData.put(TYPE, deadlineMessage.type().toString());
            jobData.put(MESSAGE_TIMESTAMP, deadlineMessage.timestamp().toString());

            SerializedObject<byte[]> serializedDeadlinePayload =
                    serializer.serialize(deadlineMessage.payload(), byte[].class);
            jobData.put(SERIALIZED_MESSAGE_PAYLOAD, serializedDeadlinePayload.getData());
            jobData.put(MESSAGE_TYPE, serializedDeadlinePayload.getType().getName());
            jobData.put(MESSAGE_REVISION, serializedDeadlinePayload.getType().getRevision());

            SerializedObject<byte[]> serializedDeadlineMetadata =
                    serializer.serialize(deadlineMessage.metadata(), byte[].class);
            jobData.put(MESSAGE_METADATA, serializedDeadlineMetadata.getData());
        }

        private static void putDeadlineScope(JobDataMap jobData, ScopeDescriptor deadlineScope, Serializer serializer) {
            SerializedObject<byte[]> serializedDeadlineScope = serializer.serialize(deadlineScope, byte[].class);
            jobData.put(SERIALIZED_DEADLINE_SCOPE, serializedDeadlineScope.getData());
            jobData.put(SERIALIZED_DEADLINE_SCOPE_CLASS_NAME, serializedDeadlineScope.getType().getName());
        }

        /**
         * Extracts a {@link DeadlineMessage} from provided {@code jobDataMap}.
         *
         * @param serializer the {@link Serializer} used to deserialize the contents of the given {@code} jobDataMap}
         *                   into a {@link DeadlineMessage}
         * @param jobDataMap the {@link JobDataMap} which should contain a {@link DeadlineMessage}
         * @return the {@link DeadlineMessage} pulled from the {@code jobDataMap}
         */
        public static DeadlineMessage deadlineMessage(Serializer serializer, JobDataMap jobDataMap) {
            return new GenericDeadlineMessage(
                    (String) jobDataMap.get(DEADLINE_NAME),
                    (String) jobDataMap.get(MESSAGE_ID),
                    MessageType.fromString((String) jobDataMap.get(TYPE)),
                    deserializeDeadlinePayload(serializer, jobDataMap),
                    deserializeDeadlineMetadata(serializer, jobDataMap),
                    retrieveDeadlineTimestamp(jobDataMap)
            );
        }

        private static Object deserializeDeadlinePayload(Serializer serializer, JobDataMap jobDataMap) {
            SimpleSerializedObject<byte[]> serializedDeadlinePayload = new SimpleSerializedObject<>(
                    (byte[]) jobDataMap.get(SERIALIZED_MESSAGE_PAYLOAD),
                    byte[].class,
                    (String) jobDataMap.get(MESSAGE_TYPE),
                    (String) jobDataMap.get(MESSAGE_REVISION)
            );
            return serializer.deserialize(serializedDeadlinePayload);
        }

        private static Map<String, String> deserializeDeadlineMetadata(Serializer serializer, JobDataMap jobDataMap) {
            SimpleSerializedObject<byte[]> serializedDeadlineMetadata = new SimpleSerializedObject<>(
                    (byte[]) jobDataMap.get(MESSAGE_METADATA), byte[].class, Metadata.class.getName(), null
            );
            return serializer.deserialize(serializedDeadlineMetadata);
        }

        private static Instant retrieveDeadlineTimestamp(JobDataMap jobDataMap) {
            Object timestamp = jobDataMap.get(MESSAGE_TIMESTAMP);
            if (timestamp instanceof String) {
                return Instant.parse(timestamp.toString());
            }
            return Instant.ofEpochMilli((long) timestamp);
        }

        /**
         * Extracts a {@link ScopeDescriptor} describing the deadline {@link Scope}, pulled
         * from provided {@code jobDataMap}.
         *
         * @param serializer the {@link Serializer} used to deserialize the contents of the given {@code} jobDataMap}
         *                   into a {@link ScopeDescriptor}
         * @param jobDataMap the {@link JobDataMap} which should contain a {@link ScopeDescriptor}
         * @return the {@link ScopeDescriptor} describing the deadline {@link Scope}, pulled
         * from provided {@code jobDataMap}
         */
        public static ScopeDescriptor deadlineScope(Serializer serializer, JobDataMap jobDataMap) {
            SimpleSerializedObject<byte[]> serializedDeadlineScope = new SimpleSerializedObject<>(
                    (byte[]) jobDataMap.get(SERIALIZED_DEADLINE_SCOPE), byte[].class,
                    (String) jobDataMap.get(SERIALIZED_DEADLINE_SCOPE_CLASS_NAME), null
            );
            return serializer.deserialize(serializedDeadlineScope);
        }
    }
}
