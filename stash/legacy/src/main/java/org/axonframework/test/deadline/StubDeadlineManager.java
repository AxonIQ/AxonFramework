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

package org.axonframework.test.deadline;

import org.jspecify.annotations.Nullable;
import org.axonframework.common.ObjectUtils;
import org.axonframework.common.Registration;
import org.axonframework.deadline.DeadlineManager;
import org.axonframework.deadline.DeadlineMessage;
import org.axonframework.deadline.GenericDeadlineMessage;
import org.axonframework.messaging.core.DefaultMessageDispatchInterceptorChain;
import org.axonframework.messaging.core.GenericMessage;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageDispatchInterceptor;
import org.axonframework.messaging.core.MessageHandlerInterceptor;
import org.axonframework.messaging.core.MessageHandlerInterceptorChain;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.ResultMessage;
import org.axonframework.messaging.core.ScopeDescriptor;
import org.axonframework.messaging.unitofwork.LegacyDefaultUnitOfWork;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.test.FixtureExecutionException;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.NavigableSet;
import java.util.NoSuchElementException;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Stub implementation of {@link DeadlineManager}. Records all scheduled, canceled and met deadlines.
 *
 * @author Milan Savic
 * @author Steven van Beelen
 * @since 3.3
 */
public class StubDeadlineManager implements DeadlineManager {

    private final NavigableSet<ScheduledDeadlineInfo> scheduledDeadlines = new TreeSet<>();
    private final List<ScheduledDeadlineInfo> triggeredDeadlines = new CopyOnWriteArrayList<>();
    private final AtomicInteger deadlineCounter = new AtomicInteger(0);
    private final List<MessageDispatchInterceptor<? super DeadlineMessage>> dispatchInterceptors =
            new CopyOnWriteArrayList<>();
    private final List<MessageHandlerInterceptor<DeadlineMessage>> handlerInterceptors =
            new CopyOnWriteArrayList<>();
    private Instant currentDateTime;

    /**
     * Initializes the manager with {@link ZonedDateTime#now()} as current time.
     */
    public StubDeadlineManager() {
        this(ZonedDateTime.now());
    }

    /**
     * Initializes the manager with provided {@code currentDateTime} as current time.
     *
     * @param currentDateTime The instance to use as current date and time
     */
    public StubDeadlineManager(TemporalAccessor currentDateTime) {
        this.currentDateTime = Instant.from(currentDateTime);
    }

    /**
     * Resets the initial "current time" of this manager. Must be called before any deadlines are scheduled.
     *
     * @param currentDateTime The instant to use as the current date and time
     * @throws IllegalStateException when calling this method after deadlines are scheduled
     */
    public void initializeAt(TemporalAccessor currentDateTime) throws IllegalStateException {
        if (!scheduledDeadlines.isEmpty()) {
            throw new IllegalStateException("Initializing the deadline manager at a specific dateTime must take place "
                                                    + "before any deadlines are scheduled");
        }
        this.currentDateTime = Instant.from(currentDateTime);
    }

    @Override
    public String schedule(Instant triggerDateTime,
                           String deadlineName,
                           Object payloadOrMessage,
                           ScopeDescriptor deadlineScope) {
        DeadlineMessage scheduledMessage =
                processDispatchInterceptors(asDeadlineMessage(deadlineName, payloadOrMessage, triggerDateTime));

        scheduledDeadlines.add(new ScheduledDeadlineInfo(triggerDateTime,
                                                         deadlineName,
                                                         scheduledMessage.identifier(),
                                                         deadlineCounter.getAndIncrement(),
                                                         scheduledMessage,
                                                         deadlineScope));
        return scheduledMessage.identifier();
    }

    private static DeadlineMessage asDeadlineMessage(String deadlineName,
                                                     @Nullable Object messageOrPayload,
                                                     Instant expiryTime) {
        if (messageOrPayload instanceof Message) {
            return new GenericDeadlineMessage(
                    deadlineName, (Message) messageOrPayload, () -> expiryTime
            );
        }
        MessageType type = new MessageType(ObjectUtils.nullSafeTypeOf(messageOrPayload));
        return new GenericDeadlineMessage(
                deadlineName, new GenericMessage(type, messageOrPayload), () -> expiryTime
        );
    }

    @Override
    public String schedule(Duration triggerDuration,
                           String deadlineName,
                           Object payloadOrMessage,
                           ScopeDescriptor deadlineScope) {
        return schedule(currentDateTime.plus(triggerDuration), deadlineName, payloadOrMessage, deadlineScope);
    }

    @Override
    public void cancelSchedule(String deadlineName, String scheduleId) {
        scheduledDeadlines.removeIf(
                scheduledDeadline -> scheduledDeadline.getDeadlineName().equals(deadlineName)
                        && scheduledDeadline.getScheduleId().equals(scheduleId)
        );
    }

    @Override
    public void cancelAll(String deadlineName) {
        scheduledDeadlines.removeIf(scheduledDeadline -> scheduledDeadline.getDeadlineName().equals(deadlineName));
    }

    @Override
    public void cancelAllWithinScope(String deadlineName, ScopeDescriptor scope) {
        scheduledDeadlines.removeIf(
                scheduledDeadline -> scheduledDeadline.getDeadlineName().equals(deadlineName)
                        && scheduledDeadline.getDeadlineScope().equals(scope)
        );
    }

    /**
     * Return all scheduled deadlines which have not been met (yet).
     *
     * @return all scheduled deadlines which have not been met (yet)
     */
    public List<ScheduledDeadlineInfo> getScheduledDeadlines() {
        return new ArrayList<>(scheduledDeadlines);
    }

    /**
     * Return all triggered deadlines.
     *
     * @return all triggered deadlines
     */
    public List<ScheduledDeadlineInfo> getTriggeredDeadlines() {
        return Collections.unmodifiableList(triggeredDeadlines);
    }

    /**
     * Return the current date and time as an {@link Instant} as is being used by this {@link DeadlineManager}.
     *
     * @return the current date and time used by the manager
     */
    public Instant getCurrentDateTime() {
        return currentDateTime;
    }

    /**
     * Advances the "current time" of the manager to the next scheduled deadline, and returns that deadline. In theory,
     * this may cause "current time" to move backwards.
     *
     * @return {@link ScheduledDeadlineInfo} of the first scheduled deadline
     */
    public ScheduledDeadlineInfo advanceToNextTrigger() {
        ScheduledDeadlineInfo nextItem = scheduledDeadlines.pollFirst();
        if (nextItem == null) {
            throw new NoSuchElementException("There are no scheduled deadlines");
        }
        if (nextItem.getScheduleTime().isAfter(currentDateTime)) {
            currentDateTime = nextItem.getScheduleTime();
        }
        triggeredDeadlines.add(nextItem);
        return nextItem;
    }

    /**
     * Advances time to the given {@code newDateTime} and invokes the given {@code deadlineConsumer} for each deadline
     * scheduled until that time.
     *
     * @param newDateTime      the time to advance the "current time" of the manager to
     * @param deadlineConsumer the consumer to invoke for each deadline to trigger
     */
    public void advanceTimeTo(Instant newDateTime, DeadlineConsumer deadlineConsumer) {
        while (!scheduledDeadlines.isEmpty() && !scheduledDeadlines.first().getScheduleTime().isAfter(newDateTime)) {
            ScheduledDeadlineInfo scheduledDeadlineInfo = advanceToNextTrigger();
            DeadlineMessage consumedMessage = consumeDeadline(deadlineConsumer, scheduledDeadlineInfo);
            triggeredDeadlines.remove(scheduledDeadlineInfo);
            triggeredDeadlines.add(scheduledDeadlineInfo.recreateWithNewMessage(consumedMessage));
        }
        if (newDateTime.isAfter(currentDateTime)) {
            currentDateTime = newDateTime;
        }
    }

    /**
     * Advances time by the given {@code duration} and invokes the given {@code deadlineConsumer} for each deadline
     * scheduled until that time.
     *
     * @param duration         the amount of time to advance the "current time" of the manager with
     * @param deadlineConsumer the consumer to invoke for each deadline to trigger
     */
    public void advanceTimeBy(Duration duration, DeadlineConsumer deadlineConsumer) {
        advanceTimeTo(currentDateTime.plus(duration), deadlineConsumer);
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

    @SuppressWarnings("unchecked")
    private DeadlineMessage processDispatchInterceptors(DeadlineMessage message) {
        return new DefaultMessageDispatchInterceptorChain<>(
                dispatchInterceptors
        ).proceed(message, null)
         .first()
         .<DeadlineMessage>cast()
         .peek()
         .map(MessageStream.Entry::message)
         .get(); // TODO reintegrate as part of #3065
    }

    private DeadlineMessage consumeDeadline(DeadlineConsumer deadlineConsumer,
                                            ScheduledDeadlineInfo scheduledDeadlineInfo) {
        LegacyDefaultUnitOfWork<? extends DeadlineMessage> uow =
                LegacyDefaultUnitOfWork.startAndGet(scheduledDeadlineInfo.deadlineMessage());

        Iterator<MessageHandlerInterceptor<DeadlineMessage>> iterator = handlerInterceptors.iterator();

        MessageHandlerInterceptorChain<DeadlineMessage> chain = new MessageHandlerInterceptorChain<>() {
            @Override
            public MessageStream<?> proceed(DeadlineMessage message,
                                            ProcessingContext context) {
                try {
                    if (iterator.hasNext()) {
                        return iterator.next().interceptOnHandle(message, context, this);
                    } else {

                        deadlineConsumer.consume(scheduledDeadlineInfo.getDeadlineScope(), message);
                        return MessageStream.empty();
                    }
                } catch (Exception e) {
                    return MessageStream.failed(e);
                }
            }
        };

        // TODO reintegrate as part of #3065
        ResultMessage resultMessage = uow.executeWithResult((ctx) -> {
            var processingResult = chain.proceed(uow.getMessage(), ctx);
            if (!processingResult.hasNextAvailable()) {
                return uow.getExecutionResult() != null ? uow.getExecutionResult() : uow.getMessage();
            }
            return processingResult;
        });
        if (resultMessage != null) {
            if (resultMessage.payload() instanceof Throwable e) {
                throw new FixtureExecutionException("Exception occurred while handling the deadline", e);
            }
            return (DeadlineMessage) resultMessage.payload();
        } else {
            return null;
        }
    }
}
