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

package org.axonframework.test.eventscheduler;

import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.GenericEventMessage;
import org.axonframework.messaging.eventhandling.scheduling.EventScheduler;
import org.axonframework.messaging.eventhandling.scheduling.ScheduleToken;
import org.axonframework.messaging.core.GenericMessage;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageType;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.List;
import java.util.NavigableSet;
import java.util.NoSuchElementException;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * EventScheduler implementation that uses it's own concept of "Current Time" for the purpose of testing. Instead of
 * publishing events, it allows (test) classes to obtain a reference to scheduled events for a certain time frame.
 * <p/>
 * To obtain scheduled events, the conceptual "current time" of the Scheduler must be advanced. All events that have
 * been scheduled within the advanced time frame are returned. It is up to the calling class to decide what to do with
 * these events.
 *
 * @author Allard Buijze
 * @since 1.1
 */
public class StubEventScheduler implements EventScheduler {

    private final NavigableSet<StubScheduleToken> scheduledEvents = new TreeSet<>();
    private final AtomicInteger counter = new AtomicInteger(0);
    private Instant currentDateTime;

    /**
     * Creates an instance of the StubScheduler that uses the current date time as its conceptual "current time".
     * Unlike
     * the real "current time", the time of the Event Scheduler is fixed.
     */
    public StubEventScheduler() {
        this(ZonedDateTime.now());
    }

    /**
     * Creates an instance of the StubScheduler that uses the given {@code currentDateTime} as its conceptual
     * "current time".
     *
     * @param currentDateTime The instant to use as current Date and Time
     */

    public StubEventScheduler(TemporalAccessor currentDateTime) {
        this.currentDateTime = Instant.from(currentDateTime);
    }

    /**
     * Resets the initial "current time" of this SubEventScheduler. Must be called before any events are scheduled
     *
     * @param currentDateTime The instant to use as the current Date and Time
     * @throws IllegalStateException when calling this method after events are scheduled
     */
    public void initializeAt(TemporalAccessor currentDateTime) {
        if (!scheduledEvents.isEmpty()) {
            throw new IllegalStateException("Initializing the scheduler at a specific dateTime must take place "
                                                    + "before any events are scheduled");
        }
        this.currentDateTime = Instant.from(currentDateTime);
    }


    @SuppressWarnings("unchecked")
    private static <P> EventMessage asEventMessage(Object event) {
        if (event instanceof EventMessage) {
            return (EventMessage) event;
        } else if (event instanceof Message message) {
            return new GenericEventMessage(message);
        }
        return new GenericEventMessage(
                new GenericMessage(new MessageType(event.getClass()), event)
        );
    }

    @Override
    public ScheduleToken schedule(Instant triggerDateTime, Object event) {
        EventMessage eventMessage = asEventMessage(event);
        StubScheduleToken token = new StubScheduleToken(triggerDateTime, eventMessage, counter.getAndIncrement());
        scheduledEvents.add(token);
        return token;
    }

    @Override
    public ScheduleToken schedule(Duration triggerDuration, Object event) {
        EventMessage eventMessage = asEventMessage(event);
        Instant scheduleTime = currentDateTime.plus(triggerDuration);
        StubScheduleToken token = new StubScheduleToken(scheduleTime, eventMessage, counter.getAndIncrement());
        scheduledEvents.add(token);
        return token;
    }

    @SuppressWarnings({"SuspiciousMethodCalls"})
    @Override
    public void cancelSchedule(ScheduleToken scheduleToken) {
        if (!(scheduleToken instanceof StubScheduleToken)) {
            throw new IllegalArgumentException("Wrong token type. This token was not provided by this scheduler");
        }
        scheduledEvents.remove(scheduleToken);
    }

    /**
     * Returns a view of all the scheduled Events at the time this method is called.
     *
     * @return a view of all the scheduled Events at the time this method is called
     */
    public List<ScheduledItem> getScheduledItems() {
        return new ArrayList<>(scheduledEvents);
    }

    /**
     * Returns the "Current Date Time" as used by the scheduler.
     *
     * @return the "Current Date Time" as used by the scheduler
     */
    public Instant getCurrentDateTime() {
        return currentDateTime;
    }

    /**
     * Advances the "current time" of the scheduler to the next scheduled Event, and returns that event. In theory,
     * this
     * may cause "current time" to move backwards.
     *
     * @return the first event scheduled
     */
    public EventMessage advanceToNextTrigger() {
        StubScheduleToken nextItem = scheduledEvents.pollFirst();
        if (nextItem == null) {
            throw new NoSuchElementException("There are no scheduled events");
        }
        if (nextItem.getScheduleTime().isAfter(currentDateTime)) {
            currentDateTime = nextItem.getScheduleTime();
        }
        return nextItem.getEvent();
    }

    /**
     * Advance time to the given {@code newDateTime} and invokes the given {@code eventConsumer} for each
     * event scheduled for publication until that time.
     *
     * @param newDateTime   The time to advance the "current time" of the scheduler to
     * @param eventConsumer The function to invoke for each event to trigger
     */
    public void advanceTimeTo(Instant newDateTime, EventConsumer<EventMessage> eventConsumer) {
        while (!scheduledEvents.isEmpty() && !scheduledEvents.first().getScheduleTime().isAfter(newDateTime)) {
            eventConsumer.accept(advanceToNextTrigger());
        }
        if (newDateTime.isAfter(currentDateTime)) {
            currentDateTime = newDateTime;
        }
    }

    /**
     * Advance time by the given {@code duration} and invokes the given {@code eventConsumer} for each
     * event scheduled for publication until that time.
     *
     * @param duration      The amount of time to advance the "current time" of the scheduler with
     * @param eventConsumer The function to invoke for each event to trigger
     */
    public void advanceTimeBy(Duration duration, EventConsumer<EventMessage> eventConsumer) {
        advanceTimeTo(currentDateTime.plus(duration), eventConsumer);
    }
}
